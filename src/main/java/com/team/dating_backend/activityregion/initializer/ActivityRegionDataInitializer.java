package com.team.dating_backend.activityregion.initializer;

import com.team.dating_backend.activityregion.entity.ActivityRegion;
import com.team.dating_backend.activityregion.repository.ActivityRegionRepository;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ActivityRegionDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ActivityRegionDataInitializer.class);
    private static final String CSV_PATH = "data/activity-regions-2025.csv";
    private static final String HEADER =
            "region_code,province_name,region_name,"
                    + "representative_latitude,representative_longitude";

    private final ActivityRegionRepository activityRegionRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws IOException {
        List<ActivityRegion> regions = readRegions();
        long persistedCount = activityRegionRepository.count();

        if (persistedCount == 0) {
            activityRegionRepository.saveAll(regions);
            log.info("Loaded {} activity regions from {}", regions.size(), CSV_PATH);
            return;
        }

        validatePersistedRegions(regions, persistedCount);
        log.info("Activity region data is already loaded");
    }

    private List<ActivityRegion> readRegions() throws IOException {
        ClassPathResource resource = new ClassPathResource(CSV_PATH);

        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            if (!HEADER.equals(reader.readLine())) {
                throw new IllegalStateException("Invalid activity region CSV header");
            }

            List<ActivityRegion> regions = new ArrayList<>();
            Set<String> regionCodes = new HashSet<>();
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                ActivityRegion region = toActivityRegion(line);
                if (!regionCodes.add(region.getRegionCode())) {
                    throw new IllegalStateException("Duplicate activity region code in CSV");
                }
                regions.add(region);
            }

            if (regions.isEmpty()) {
                throw new IllegalStateException("Activity region CSV must not be empty");
            }
            return regions;
        }
    }

    private ActivityRegion toActivityRegion(String line) {
        String[] values = line.split(",", -1);
        if (values.length != 5) {
            throw new IllegalStateException("Invalid activity region CSV row");
        }

        String regionCode = values[0].trim();
        String provinceName = values[1].trim();
        String regionName = values[2].trim();
        BigDecimal latitude = new BigDecimal(values[3].trim());
        BigDecimal longitude = new BigDecimal(values[4].trim());

        if (!regionCode.matches("\\d{5}") || provinceName.isBlank() || regionName.isBlank()) {
            throw new IllegalStateException("Invalid activity region CSV value");
        }
        if (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0
                || longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new IllegalStateException("Invalid activity region coordinate");
        }

        return new ActivityRegion(regionCode, provinceName, regionName, latitude, longitude);
    }

    private void validatePersistedRegions(List<ActivityRegion> regions, long persistedCount) {
        if (persistedCount != regions.size()) {
            throw new IllegalStateException("Activity region data is partially loaded");
        }

        Set<String> expectedCodes = new HashSet<>();
        for (ActivityRegion region : regions) {
            expectedCodes.add(region.getRegionCode());
        }

        Set<String> persistedCodes = new HashSet<>();
        for (ActivityRegion region : activityRegionRepository.findAll()) {
            persistedCodes.add(region.getRegionCode());
        }

        if (!persistedCodes.equals(expectedCodes)) {
            throw new IllegalStateException("Activity region data does not match the CSV");
        }
    }
}
