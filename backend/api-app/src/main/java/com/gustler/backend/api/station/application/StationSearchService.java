package com.gustler.backend.api.station.application;

import com.gustler.backend.api.error.ServiceUnavailableException;
import com.gustler.backend.api.station.domain.MapStation;
import com.gustler.backend.api.station.domain.StationSearchQuery;
import java.util.List;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Service;

/**
 * 정류장 이름 검색. 검색어 정규화와 결과 개수 상한을 정한다.
 */
@Service
public class StationSearchService {

    public static final int LIMIT = 30;

    private final StationReadRepository repository;

    public StationSearchService(StationReadRepository repository) {
        this.repository = repository;
    }

    public List<MapStation> search(String rawQuery) {
        StationSearchQuery query = StationSearchQuery.parse(rawQuery);
        try {
            return repository.search(query, LIMIT);
        } catch (DataAccessResourceFailureException unavailable) {
            throw new ServiceUnavailableException();
        }
    }
}
