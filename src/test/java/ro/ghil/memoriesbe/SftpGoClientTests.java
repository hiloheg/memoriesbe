package ro.ghil.memoriesbe;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ro.ghil.memoriesbe.config.Props;
import ro.ghil.memoriesbe.sftpgo.CredentialGenerator;
import ro.ghil.memoriesbe.sftpgo.SftpGoClient;

class SftpGoClientTests {

	@Test
	void fetchesAdminTokenThenCreatesR2User() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		var client = new SftpGoClient(builder,
				new Props.SftpGo("http://sftpgo", "admin", "pw"),
				new Props.R2("https://r2.example", "memories", "auto", "AK", "SK"));

		server.expect(requestTo("http://sftpgo/api/v2/token"))
				.andExpect(method(HttpMethod.GET))
				.andExpect(header("Authorization", "Basic YWRtaW46cHc="))
				.andRespond(withSuccess("{\"access_token\":\"tok\",\"expires_at\":\"" + Instant.now().plusSeconds(3600) + "\"}",
						MediaType.APPLICATION_JSON));
		server.expect(requestTo("http://sftpgo/api/v2/users"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("Authorization", "Bearer tok"))
				.andExpect(jsonPath("$.username").value("cam_x"))
				.andExpect(jsonPath("$.expiration_date").value(1_800_000_000_000L))
				.andExpect(jsonPath("$.filesystem.provider").value(1))
				.andExpect(jsonPath("$.filesystem.s3config.bucket").value("memories"))
				.andExpect(jsonPath("$.filesystem.s3config.key_prefix").value("events/e/c/"))
				.andExpect(jsonPath("$.filesystem.s3config.access_secret.payload").value("SK"))
				.andExpect(jsonPath("$.permissions['/']").value(org.hamcrest.Matchers.contains("list", "upload", "create_dirs")))
				.andRespond(withSuccess());

		client.createUser("cam_x", "secret", "events/e/c/", Instant.ofEpochMilli(1_800_000_000_000L));
		server.verify();
	}

	@Test
	void generatesDistinctCredentials() {
		var gen = new CredentialGenerator();
		assertTrue(gen.username().matches("cam_[a-z2-9]{8}"));
		assertTrue(gen.password().length() == 20);
		assertTrue(!gen.password().equals(gen.password()));
	}
}
