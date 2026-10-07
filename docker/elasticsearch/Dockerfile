# Elasticsearch with Nori, the official Korean morphological analyzer ("무선이어폰" -> 무선 + 이어폰).
# Version matches the Java client Spring Boot 3.5 manages (8.18): the client must not be newer than the server.
FROM docker.elastic.co/elasticsearch/elasticsearch:8.18.8
RUN bin/elasticsearch-plugin install --batch analysis-nori
