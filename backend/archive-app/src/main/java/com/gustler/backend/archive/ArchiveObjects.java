package com.gustler.backend.archive;

/** 작은 파일을 키로 두고 다시 읽는다. 행별 영구 DB 장부는 만들지 않는다. */
public interface ArchiveObjects {
    /**
     * 파일을 키에 둔다. 같은 키가 이미 있으면 덮어쓰지 않고 그대로 둔다.
     *
     * <p>저장된 내용이 {@code bytes} 와 같다는 것은 보장하지 않는다. 내보내기는 장부에 적기 전과 줄을 지우기 전에 manifest 와 모든
     * 파일을 {@link #read} 로 다시 읽어 해시와 줄 수를 확인하고, 그 확인이 저장 내용을 지킨다.
     */
    void putVerified(String key, byte[] bytes);

    byte[] read(String key, final int limit);

    /** {@code prefix} 아래 파일이 담은 줄을 DB에서 지웠음을 알린다. 파일을 계속 보관하는 저장소는 아무 일도 하지 않는다. */
    void release(String prefix);
}
