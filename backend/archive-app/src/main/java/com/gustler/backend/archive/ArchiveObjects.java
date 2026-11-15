package com.gustler.backend.archive;

/** 작은 파일을 검증하며 전달한다. 행별 영구 DB 장부는 만들지 않는다. */
public interface ArchiveObjects {
    void putVerified(String key, byte[] bytes);

    byte[] read(String key, final int limit);

    /** {@code prefix} 아래 파일이 담은 줄을 DB에서 지웠음을 알린다. 파일을 계속 보관하는 저장소는 아무 일도 하지 않는다. */
    void release(String prefix);
}
