package com.gustler.localdata;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureVector;
import com.gustler.backend.forecasting.infrastructure.bundle.FileModelBundleLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** 합성 번들의 고정 입력·출력을 확인한다. 모델 정확도 검증이 아니다. */
public final class ModelParityCheck {
    public static void main(String[] args) {
        try {
            var release = new FileModelBundleLoader().filesUnder(ReferenceBundle.DIRECTORY).load();
            LocalData.require(ReferenceBundle.DIGEST.equals(release.bundleDigest()),"MODEL_PARITY_IDENTITY");
            var json=JsonMapper.builder().build();
            var golden=json.readTree(Files.readAllBytes(Path.of(ReferenceBundle.DIRECTORY).resolve("golden.json")));
            double[] seat=new double[88];
            double[] probability=new double[80];
            for (int i=0;i<seat.length;i++) { seat[i]=golden.path("seat_inputs").get(0).get(i).asDouble(); }
            for (int i=0;i<probability.length;i++) { probability[i]=golden.path("probability_inputs").get(0).get(i).asDouble(); }
            var result=release.model().predict(new AlightingAwareFeatureVector(seat,probability));
            LocalData.require(Math.abs(result.expectedSeats()-golden.path("expected_seats").get(0).asDouble())<1e-9
                && Math.abs(result.fullChance()-golden.path("expected_departure_full").get(0).asDouble())<1e-9,"MODEL_PARITY_GOLDEN");
            System.out.println(json.writeValueAsString(Map.of("status","passed","cases",1,"syntheticFixture",true,
                "accuracyEvaluated",false,"releaseId",release.releaseId(),"bundleDigest",release.bundleDigest())));
        } catch (Exception error) {
            System.err.println("{\"status\":\"failed\",\"code\":\"MODEL_PARITY\"}");
            System.exit(1);
        }
    }
}
