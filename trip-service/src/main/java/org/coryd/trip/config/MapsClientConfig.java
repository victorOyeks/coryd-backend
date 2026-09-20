package org.coryd.trip.config;

import org.coryd.common.http.HttpTimeouts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class MapsClientConfig {

    @Bean
    public RestClient mapsRestClient(@Value("${google.maps.base-url}") String mapsBaseUrl) {
        return HttpTimeouts.builder()
                .baseUrl(mapsBaseUrl)
                .build();
    }
}
