# Feature Specification: Contact Form

**Feature Branch**: `006-contact-form`
**Created**: 2026-02-21
**Status**: Draft
**Input**: Contact form submission with spam protection and email notification delivery

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Submit Contact Message (Priority: P1)

A visitor to the website wants to send a message to the site owner. They navigate to the contact section, fill out their name, email address, subject, and message, then submit the form. The system validates their input, verifies they are not a bot, and sends their message. The visitor sees a clear confirmation that their message was sent successfully.

**Why this priority**: This is the core value of the feature - enabling direct communication between visitors and the site owner. Without this, the feature has no purpose.

**Independent Test**: Can be fully tested by filling out and submitting the contact form with valid information, then verifying the confirmation message appears and the site owner receives an email with the submission details.

**Acceptance Scenarios**:

1. **Given** a visitor is on the homepage contact section, **When** they fill out all required fields (first name, last name, email, subject, message) with valid data and pass spam verification, **Then** they see a success message confirming their submission was sent
2. **Given** a visitor submits a valid contact form, **When** the submission is processed, **Then** the site owner receives an email at their designated address containing all submitted information (name, email, subject, message, and the page the visitor came from if available)
3. **Given** a visitor fills out the contact form, **When** they attempt to submit without completing all required fields, **Then** they see clear error messages indicating which fields need to be completed
4. **Given** a visitor fills out the contact form, **When** they enter an invalid email address format, **Then** they see an error message indicating the email format is incorrect
5. **Given** a visitor attempts to submit the contact form, **When** the spam verification fails, **Then** they see an error message and cannot submit the form
6. **Given** a visitor submits the contact form, **When** the email delivery fails on the server, **Then** they see an error message indicating the submission could not be completed and to try again later

---

### User Story 2 - View Contact Information (Priority: P2)

A visitor wants to contact the site owner through alternative methods. They view the contact section and see the owner's location, phone number, and email addresses displayed clearly. They can click the phone number to initiate a call on their mobile device, or click an email address to open their email client.

**Why this priority**: Provides alternative contact methods for visitors who prefer direct communication or need immediate assistance. Enhances accessibility and user choice.

**Independent Test**: Can be fully tested by viewing the contact section and verifying all contact information is visible and that phone/email links work correctly when clicked.

**Acceptance Scenarios**:

1. **Given** a visitor views the contact section, **When** the page loads, **Then** they see the site owner's location, phone number, primary email address, and secondary email address displayed clearly
2. **Given** a visitor is viewing the contact information on a mobile device, **When** they tap the phone number, **Then** their device's phone dialer opens with the number pre-filled
3. **Given** a visitor clicks on an email address, **When** the link is activated, **Then** their default email client opens with a new message addressed to that email
4. **Given** a visitor views the contact information, **When** they see the email addresses, **Then** they can clearly distinguish between the primary and secondary email addresses

---

### Edge Cases

- What happens when a visitor submits the form but has a slow or unstable internet connection?
- How does the system handle extremely long message content (e.g., several paragraphs)?
- What happens if the spam verification service is temporarily unavailable?
- How does the system handle special characters or emojis in form fields?
- What happens when a visitor submits multiple forms in quick succession?
- How does the system handle email addresses with uncommon but valid formats (e.g., international domains)?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide a contact form with five required input fields: first name, last name, email address, subject, and message
- **FR-002**: System MUST validate that all five form fields contain data before allowing submission
- **FR-003**: System MUST validate that the email field contains a properly formatted email address
- **FR-004**: System MUST verify that the submission is from a human user and not an automated bot before processing
- **FR-005**: System MUST send an email notification to simon.rowe@gmail.com when a valid form is submitted
- **FR-006**: Email notification MUST include the visitor's first name, last name, email address, subject, message, and the referring page URL (if available)
- **FR-007**: Email notification MUST be sent from the address simon@simonjamesrowe.com
- **FR-008**: System MUST display a success message to the visitor after successful form submission
- **FR-009**: System MUST display clear error messages when submission fails (validation errors, spam verification failure, or server errors)
- **FR-010**: System MUST display contact information including location, phone number, primary email address, and secondary email address
- **FR-011**: Phone number MUST be displayed as a clickable link that initiates a phone call when activated
- **FR-012**: Email addresses MUST be displayed as clickable links that open the user's default email client when activated
- **FR-013**: System MUST accept the HTTP endpoint POST /contact-us for form submissions
- **FR-014**: System MUST capture and include the HTTP Referer header value (when present) in the email notification to indicate which page the visitor submitted from

### Key Entities

- **Contact Submission**: Represents a message sent by a website visitor, including their first name, last name, email address, subject line, message content, and optionally the referring page URL
- **Contact Information**: Represents the site owner's contact details displayed to visitors, including physical location, phone number, primary email address (for business inquiries), and secondary email address (alternative contact method)

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Visitors can successfully submit a contact message and receive confirmation within 5 seconds under normal network conditions
- **SC-002**: 95% of legitimate contact form submissions result in successful email delivery to the site owner
- **SC-003**: Spam verification prevents at least 99% of automated bot submissions while allowing legitimate human submissions
- **SC-004**: All required form fields display clear, actionable error messages when validation fails, enabling visitors to correct issues on their first retry
- **SC-005**: Phone and email links work correctly on 100% of modern browsers and mobile devices, opening the appropriate native application
- **SC-006**: Contact form and information display correctly and remain fully functional across all viewport sizes (mobile, tablet, desktop)
