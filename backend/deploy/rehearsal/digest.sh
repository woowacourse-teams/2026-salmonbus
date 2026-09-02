stable_digest() {
  unzip -v "$1" \
    | awk 'NF>=8 {print $7, $8}' \
    | grep -v 'META-INF/build-info.properties' \
    | sort | sha256sum | cut -d' ' -f1
}
