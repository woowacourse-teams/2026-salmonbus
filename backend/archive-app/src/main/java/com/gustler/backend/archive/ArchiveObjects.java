package com.gustler.backend.archive;

/** 작은 파일을 검증하며 전달한다. 행별 영구 DB 장부는 만들지 않는다. */
public interface ArchiveObjects {
    void putVerified(String key, byte[] bytes);

    byte[] read(String key, final int limit);

}
