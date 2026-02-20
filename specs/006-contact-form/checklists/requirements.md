# Requirements Checklist - Contact Form

**Spec**: 006-contact-form
**Created**: 2026-02-21
**Status**: Ready for Implementation

## Functional Requirements

### Contact Form Submission (P1)

- [x] **FR-001**: System MUST provide a contact form with five required input fields: first name, last name, email address, subject, and message
- [x] **FR-002**: System MUST validate that all five form fields contain data before allowing submission
- [x] **FR-003**: System MUST validate that the email field contains a properly formatted email address
- [x] **FR-004**: System MUST verify that the submission is from a human user and not an automated bot before processing
- [x] **FR-005**: System MUST send an email notification to simon.rowe@gmail.com when a valid form is submitted
- [x] **FR-006**: Email notification MUST include the visitor's first name, last name, email address, subject, message, and the referring page URL (if available)
- [x] **FR-007**: Email notification MUST be sent from the address simon@simonjamesrowe.com
- [x] **FR-008**: System MUST display a success message to the visitor after successful form submission
- [x] **FR-009**: System MUST display clear error messages when submission fails (validation errors, spam verification failure, or server errors)
- [x] **FR-013**: System MUST accept the HTTP endpoint POST /contact-us for form submissions
- [x] **FR-014**: System MUST capture and include the HTTP Referer header value (when present) in the email notification to indicate which page the visitor submitted from

### Contact Information Display (P2)

- [x] **FR-010**: System MUST display contact information including location, phone number, primary email address, and secondary email address
- [x] **FR-011**: Phone number MUST be displayed as a clickable link that initiates a phone call when activated
- [x] **FR-012**: Email addresses MUST be displayed as clickable links that open the user's default email client when activated

## User Story Acceptance Criteria

### US1: Submit Contact Message (P1)

- [x] Visitor can fill out all five required fields and submit successfully
- [x] Success message appears after successful submission
- [x] Site owner receives email with all submission details
- [x] Error messages appear for missing required fields
- [x] Error message appears for invalid email format
- [x] Error message appears when spam verification fails
- [x] Error message appears when email delivery fails

### US2: View Contact Information (P2)

- [x] Location, phone number, and both email addresses are clearly displayed
- [x] Phone number link opens device dialer on mobile
- [x] Email address links open default email client
- [x] Primary and secondary email addresses are distinguishable

## Edge Cases

- [x] System handles slow or unstable internet connections during submission
- [x] System handles extremely long message content
- [x] System handles spam verification service unavailability
- [x] System handles special characters and emojis in form fields
- [x] System handles multiple rapid submissions
- [x] System handles uncommon but valid email address formats

## Success Criteria

- [x] **SC-001**: Form submission and confirmation completes within 5 seconds
- [x] **SC-002**: 95% of legitimate submissions result in successful email delivery
- [x] **SC-003**: Spam verification prevents 99%+ of bot submissions
- [x] **SC-004**: Error messages enable successful correction on first retry
- [x] **SC-005**: Phone and email links work on 100% of modern browsers/devices
- [x] **SC-006**: Form functions correctly across all viewport sizes

## Implementation Notes

All requirements are clearly defined and ready for implementation. No clarifications needed.

**Total Requirements**: 14 functional requirements
**Total Acceptance Criteria**: 11 scenarios
**Total Edge Cases**: 6 scenarios
**Total Success Criteria**: 6 measurable outcomes

**Status**: All items checked - specification is complete and implementation-ready.
