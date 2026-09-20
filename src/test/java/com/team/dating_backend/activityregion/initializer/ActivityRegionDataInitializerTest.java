package com.team.dating_backend.activityregion.initializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.activityregion.entity.ActivityRegion;
import com.team.dating_backend.activityregion.repository.ActivityRegionRepository;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ClassPathResource;

@ExtendWith(MockitoExtension.class)
class ActivityRegionDataInitializerTest {

    private static final String CSV_PATH = "data/activity-regions-2025.csv";

    @Mock private ActivityRegionRepository activityRegionRepository;

    private ActivityRegionDataInitializer activityRegionDataInitializer;

    @BeforeEach
    void setUp() {
        activityRegionDataInitializer = new ActivityRegionDataInitializer(activityRegionRepository);
    }

    @Test
    void 테이블이_비어_있으면_CSV의_모든_활동_지역을_적재한다() throws Exception {
        List<CsvActivityRegion> csvRegions = readCsvActivityRegions();
        given(activityRegionRepository.count()).willReturn(0L);

        activityRegionDataInitializer.run(new DefaultApplicationArguments());

        ArgumentCaptor<Iterable<ActivityRegion>> regionsCaptor = ArgumentCaptor.captor();
        verify(activityRegionRepository).saveAll(regionsCaptor.capture());

        List<ActivityRegion> savedRegions = toList(regionsCaptor.getValue());
        assertThat(savedRegions).hasSize(csvRegions.size());
        assertThat(savedRegions)
                .anySatisfy(
                        activityRegion -> {
                            assertThat(activityRegion.getRegionCode()).isEqualTo("11010");
                            assertThat(activityRegion.getProvinceName()).isEqualTo("서울특별시");
                            assertThat(activityRegion.getRegionName()).isEqualTo("종로구");
                            assertThat(activityRegion.getRepresentativeLatitude())
                                    .isEqualByComparingTo("37.594916");
                            assertThat(activityRegion.getRepresentativeLongitude())
                                    .isEqualByComparingTo("126.977313");
                        });
    }

    @Test
    void CSV와_동일한_활동_지역이_이미_적재되어_있으면_추가_저장을_하지_않는다() throws Exception {
        List<ActivityRegion> persistedRegions = toActivityRegions(readCsvActivityRegions());
        given(activityRegionRepository.count()).willReturn((long) persistedRegions.size());
        given(activityRegionRepository.findAll()).willReturn(persistedRegions);

        activityRegionDataInitializer.run(new DefaultApplicationArguments());

        verify(activityRegionRepository, never()).saveAll(any());
    }

    @Test
    void 일부_활동_지역만_적재되어_있으면_애플리케이션_시작에_실패한다() throws Exception {
        long csvRegionCount = readCsvActivityRegions().size();
        given(activityRegionRepository.count()).willReturn(csvRegionCount - 1);

        assertThatThrownBy(
                        () -> activityRegionDataInitializer.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Activity region data is partially loaded");

        verify(activityRegionRepository, never()).saveAll(any());
        verify(activityRegionRepository, never()).findAll();
    }

    @Test
    void 동일한_개수라도_지역_코드가_CSV와_다르면_애플리케이션_시작에_실패한다() throws Exception {
        List<ActivityRegion> persistedRegions =
                new ArrayList<>(toActivityRegions(readCsvActivityRegions()));
        persistedRegions.set(
                0,
                new ActivityRegion(
                        "99999",
                        "서울특별시",
                        "존재하지않는지역",
                        new BigDecimal("37.594916"),
                        new BigDecimal("126.977313")));
        given(activityRegionRepository.count()).willReturn((long) persistedRegions.size());
        given(activityRegionRepository.findAll()).willReturn(persistedRegions);

        assertThatThrownBy(
                        () -> activityRegionDataInitializer.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Activity region data does not match the CSV");

        verify(activityRegionRepository, never()).saveAll(any());
    }

    private List<CsvActivityRegion> readCsvActivityRegions() throws IOException {
        ClassPathResource resource = new ClassPathResource(CSV_PATH);

        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            reader.readLine();
            List<CsvActivityRegion> regions = new ArrayList<>();
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                String[] values = line.split(",", -1);
                regions.add(
                        new CsvActivityRegion(
                                values[0].trim(),
                                values[1].trim(),
                                values[2].trim(),
                                new BigDecimal(values[3].trim()),
                                new BigDecimal(values[4].trim())));
            }
            return regions;
        }
    }

    private List<ActivityRegion> toActivityRegions(List<CsvActivityRegion> csvRegions) {
        return csvRegions.stream()
                .map(
                        csvRegion ->
                                new ActivityRegion(
                                        csvRegion.regionCode(),
                                        csvRegion.provinceName(),
                                        csvRegion.regionName(),
                                        csvRegion.representativeLatitude(),
                                        csvRegion.representativeLongitude()))
                .toList();
    }

    private List<ActivityRegion> toList(Iterable<ActivityRegion> regions) {
        List<ActivityRegion> activityRegions = new ArrayList<>();
        regions.forEach(activityRegions::add);
        return activityRegions;
    }

    private record CsvActivityRegion(
            String regionCode,
            String provinceName,
            String regionName,
            BigDecimal representativeLatitude,
            BigDecimal representativeLongitude) {}
}
