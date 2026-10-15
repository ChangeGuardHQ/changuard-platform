package com.changeguard.connector;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "changeguard.outbox.enabled=false")
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class ConnectorServiceApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void contextLoads() {
	}

	@Test
	void webhookRejectsAnInvalidHmacWithProcessingServiceRegistered() throws Exception {
		mockMvc.perform(post("/api/v1/integrations/github/webhook")
				.contentType(MediaType.APPLICATION_JSON)
				.header("X-GitHub-Event", "push")
				.header("X-GitHub-Delivery", "72d3162e-cc78-11e3-81ab-4c9367dc0958")
				.header("X-Hub-Signature-256", "sha256=" + "a".repeat(64))
				.content("{}"))
				.andExpect(status().isUnauthorized());
	}

}
