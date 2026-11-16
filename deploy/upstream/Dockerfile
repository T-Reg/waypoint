# Demo upstream for App Platform: WireMock with the stub mappings baked in (App Platform can't mount files).
# Bounded request journal: the visualizer reads it to show what the upstream received.
FROM wiremock/wiremock:3.13.2
COPY mappings /home/wiremock/mappings
CMD ["--disable-banner", "--max-request-journal-entries", "2000"]
