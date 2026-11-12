package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catalogo;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Arma el {@link RestClient} hacia el servicio de catálogo, con las mismas decisiones que el de la cátedra (ADR 0004): la
 * fábrica del JDK fijada a mano, sin redirecciones y con HTTP/1.1.
 * <p>
 * Es un cliente aparte y <strong>sin cabecera por defecto</strong>: el token de servicio se firma en cada pedido, y el
 * JWT técnico de la cátedra nunca puede viajar al catálogo por compartir el cliente.
 */
@Configuration
@EnableConfigurationProperties(CatalogoApiProperties.class)
public class CatalogoClientConfiguration {

    public static final String CATALOGO_REST_CLIENT = "catalogoRestClient";

    @Bean(CATALOGO_REST_CLIENT)
    public RestClient catalogoRestClient(CatalogoApiProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.getConnectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return RestClient.builder().requestFactory(requestFactory).baseUrl(properties.getUrl().toString()).build();
    }
}
