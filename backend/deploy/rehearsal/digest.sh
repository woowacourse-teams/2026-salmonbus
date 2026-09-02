# buildspec.yml 의 post_build 에 있는 것과 같은 함수다.
# 여기서 고치면 buildspec 도 같이 고쳐야 한다
source_digest() {
  find "$@" -type f -print0 2>/dev/null \
    | LC_ALL=C sort -z \
    | xargs -0 sha256sum \
    | sha256sum | cut -d' ' -f1
}
