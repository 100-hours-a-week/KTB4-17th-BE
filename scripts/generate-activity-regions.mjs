import { mkdir, rename, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';

const PROJECT_ROOT = resolve(import.meta.dirname, '..');
const OUTPUT_PATH = resolve(PROJECT_ROOT,
    'src/main/resources/data/activity-regions-2025.csv');
const SGIS_API_BASE_URL = 'https://sgisapi.mods.go.kr/OpenAPI3';
const BOUNDARY_YEAR = '2025';

const consumerKey = requireEnvironment('SGIS_CONSUMER_KEY');
const consumerSecret = requireEnvironment('SGIS_CONSUMER_SECRET');
const accessToken = await issueAccessToken(consumerKey, consumerSecret);
const provinces = (await requestBoundaries({ accessToken, low_search: '1' }))
    .filter((feature) => /^\d{2}$/.test(String(feature.properties?.adm_cd ?? '')))
    .filter((feature) => feature.properties.adm_cd !== '00');
const regions = [];

if (provinces.length !== 17) {
    throw new Error('SGIS did not return all 17 provinces');
}

for (const province of provinces) {
    const provinceCode = getProperty(province, 'adm_cd');
    const provinceName = getProperty(province, 'adm_nm');
    const children = await requestBoundaries({
        accessToken,
        adm_cd: provinceCode,
        low_search: '1',
    });

    for (const child of children) {
        const regionCode = getProperty(child, 'adm_cd');
        if (!/^\d{5}$/.test(regionCode)) {
            continue;
        }

        const regionName = removeProvincePrefix(provinceName, getProperty(child, 'adm_nm'));
        const sourceX = getProperty(child, 'x');
        const sourceY = getProperty(child, 'y');
        const coordinate = await transformCoordinate(accessToken, sourceX, sourceY);
        regions.push({
            regionCode,
            provinceName,
            regionName,
            latitude: coordinate.posY,
            longitude: coordinate.posX,
        });
    }
}

validateRegions(regions);
await writeCsv(regions);
console.log(`Generated ${regions.length} activity regions for ${BOUNDARY_YEAR}.`);

function requireEnvironment(key) {
    const value = process.env[key];
    if (!value) {
        throw new Error(`${key} must be set in the environment`);
    }
    return value;
}

async function issueAccessToken(consumerKey, consumerSecret) {
    const response = await requestJson('/auth/authentication.json', {
        consumer_key: consumerKey,
        consumer_secret: consumerSecret,
    });
    const token = response.result?.accessToken;
    if (!token) {
        throw new Error('SGIS did not issue an access token');
    }
    return token;
}

async function requestBoundaries(parameters) {
    const response = await requestJson('/boundary/hadmarea.geojson', {
        ...parameters,
        year: BOUNDARY_YEAR,
    });
    if (!Array.isArray(response.features)) {
        throw new Error('SGIS returned an invalid boundary response');
    }
    return response.features;
}

async function transformCoordinate(accessToken, posX, posY) {
    const response = await requestJson('/transformation/transcoord.json', {
        accessToken,
        src: '5179',
        dst: '4326',
        posX,
        posY,
    });
    const coordinate = response.result;
    if (!coordinate?.posX || !coordinate?.posY) {
        throw new Error('SGIS returned an invalid transformed coordinate');
    }
    return coordinate;
}

async function requestJson(path, parameters) {
    const url = new URL(`${SGIS_API_BASE_URL}${path}`);
    for (const [key, value] of Object.entries(parameters)) {
        url.searchParams.set(key, value);
    }

    const response = await fetch(url);
    if (!response.ok) {
        throw new Error(`SGIS request failed with HTTP ${response.status}`);
    }

    const body = await response.json();
    if (String(body.errCd) !== '0') {
        throw new Error(`SGIS request failed: ${body.errMsg ?? 'unknown error'}`);
    }
    return body;
}

function getProperty(feature, key) {
    const value = feature.properties?.[key];
    if (value === undefined || value === null || String(value).trim() === '') {
        throw new Error(`SGIS boundary is missing ${key}`);
    }
    return String(value).trim();
}

function removeProvincePrefix(provinceName, regionName) {
    const prefix = `${provinceName} `;
    if (regionName.startsWith(prefix)) {
        return regionName.slice(prefix.length);
    }
    return regionName;
}

function validateRegions(regions) {
    if (regions.length === 0) {
        throw new Error('No activity regions were generated');
    }

    const codes = new Set();
    for (const region of regions) {
        if (codes.has(region.regionCode)) {
            throw new Error('Duplicate activity region code generated');
        }
        codes.add(region.regionCode);

        if (!region.provinceName || !region.regionName) {
            throw new Error('Blank activity region name generated');
        }

        const latitude = Number(region.latitude);
        const longitude = Number(region.longitude);
        if (!Number.isFinite(latitude) || !Number.isFinite(longitude)
                || latitude < -90 || latitude > 90
                || longitude < -180 || longitude > 180) {
            throw new Error('Invalid WGS84 coordinate generated');
        }
        if (latitude < 30 || latitude > 45 || longitude < 120 || longitude > 140) {
            throw new Error('Latitude and longitude may be reversed');
        }
    }
}

async function writeCsv(regions) {
    const header = 'region_code,province_name,region_name,'
        + 'representative_latitude,representative_longitude';
    const rows = regions
        .sort((left, right) => left.regionCode.localeCompare(right.regionCode))
        .map((region) => [
            region.regionCode,
            region.provinceName,
            region.regionName,
            Number(region.latitude).toFixed(6),
            Number(region.longitude).toFixed(6),
        ].join(','));
    const contents = `${header}\n${rows.join('\n')}\n`;
    const temporaryPath = `${OUTPUT_PATH}.tmp`;

    await mkdir(dirname(OUTPUT_PATH), { recursive: true });
    await writeFile(temporaryPath, contents, 'utf8');
    await rename(temporaryPath, OUTPUT_PATH);
}
