package com.simonrowe.contact;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {ContactController.class, ContactExceptionHandler.class})
class ContactControllerTest {

  @MockitoBean
  private ContactService contactService;

  @Autowired
  private MockMvc mockMvc;

  private static final String VALID_REQUEST = """
      {
        "firstName": "Jane",
        "lastName": "Doe",
        "email": "jane@example.com",
        "subject": "Hello",
        "message": "Test message content",
        "recaptchaToken": "valid-token"
      }
      """;

  @Test
  void validSubmissionReturns200() throws Exception {
    doNothing().when(contactService).submit(any(), any());

    mockMvc.perform(post("/api/contact-us")
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_REQUEST))
        .andExpect(status().isOk());
  }

  @Test
  void missingRequiredFieldsReturns400WithFieldErrors() throws Exception {
    mockMvc.perform(post("/api/contact-us")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "firstName": "",
                  "email": "not-an-email",
                  "subject": "",
                  "message": ""
                }
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors").isArray());
  }

  @Test
  void invalidEmailFormatReturns400() throws Exception {
    mockMvc.perform(post("/api/contact-us")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "firstName": "Jane",
                  "lastName": "Doe",
                  "email": "not-an-email",
                  "subject": "Hello",
                  "message": "Test message",
                  "recaptchaToken": "valid-token"
                }
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[?(@.field == 'email')]").exists());
  }

  @Test
  void recaptchaFailureReturns400() throws Exception {
    doThrow(new RecaptchaVerificationException("reCAPTCHA verification failed"))
        .when(contactService).submit(any(), any());

    mockMvc.perform(post("/api/contact-us")
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_REQUEST))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("recaptchaToken"));
  }

  @Test
  void emailDeliveryFailureReturns500() throws Exception {
    doThrow(new EmailDeliveryException(
        "Failed to send message. Please try again later.",
        new RuntimeException()
    )).when(contactService).submit(any(), any());

    mockMvc.perform(post("/api/contact-us")
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_REQUEST))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error").value("Failed to send message. Please try again later."));
  }

  @Test
  void recaptchaServiceUnavailableReturns503() throws Exception {
    doThrow(new RecaptchaServiceUnavailableException(
        "Verification service temporarily unavailable. Please try again later.",
        new RuntimeException()
    )).when(contactService).submit(any(), any());

    mockMvc.perform(post("/api/contact-us")
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_REQUEST))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").exists());
  }

  @Test
  void refererHeaderIsPassedThroughToService() throws Exception {
    doNothing().when(contactService).submit(any(), any());

    mockMvc.perform(post("/api/contact-us")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Referer", "http://simonrowe.dev/")
            .content(VALID_REQUEST))
        .andExpect(status().isOk());

    verify(contactService).submit(any(), eq("http://simonrowe.dev/"));
  }

  @Test
  void missingRefererHeaderPassesNullToService() throws Exception {
    doNothing().when(contactService).submit(any(), any());

    mockMvc.perform(post("/api/contact-us")
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_REQUEST))
        .andExpect(status().isOk());

    verify(contactService).submit(any(), isNull());
  }
}
