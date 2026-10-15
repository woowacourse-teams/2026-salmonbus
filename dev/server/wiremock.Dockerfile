FROM wiremock/wiremock:3.13.2@sha256:0d4ecb3e4dc8213fd7a4d37d6a78f6e6b553a6d2e15bd51b0999781282ac61b3
COPY local-gbis-replay.jar /var/wiremock/extensions/
COPY mappings/ /home/wiremock/mappings/
COPY data/ /local/data/
COPY replay.json /local/scenarios/replay.json
ENTRYPOINT ["/docker-entrypoint.sh", "--extensions", "com.gustler.localgbis.GbisReplayTransformer", "--max-request-journal-entries", "500", "--disable-banner", "--disable-request-logging"]
