# Demo upstream for App Platform: WireMock with the stub mappings baked in (App Platform can't mount files).
FROM wiremock/wiremock:3.13.2
COPY mappings /home/wiremock/mappings
CMD ["--disable-banner", "--no-request-journal"]
