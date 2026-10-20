package com.roadwise.backend.dto;

import com.roadwise.backend.model.Barangay;
import com.roadwise.backend.model.User;

import java.time.LocalDate;

/**
 * Safe, read-only projection of a {@link User} for API responses.
 *
 * <p>Deliberately excludes credentials and security state (password hash,
 * MFA secrets). A user response carries identity and role, never authentication
 * state or credential metadata. Returning the JPA entity directly invited
 * accidental disclosure whenever a new field was added.</p>
 */
public class UserResponseDto {

    private Long id;
    private String username;
    private String role;
    private String firstName;
    private String middleName;
    private String lastName;
    private String email;
    private String phoneNumber;
    private LocalDate birthday;
    private String gender;
    private String profilePicture;
    private String status;
    private Boolean passwordChangeRequired;
    private BarangayDto barangay;

    public UserResponseDto() {
    }

    public static UserResponseDto from(User user) {
        if (user == null) {
            return null;
        }
        UserResponseDto dto = new UserResponseDto();
        dto.id = user.getId();
        dto.username = user.getUsername();
        dto.role = user.getRole();
        dto.firstName = user.getFirstName();
        dto.middleName = user.getMiddleName();
        dto.lastName = user.getLastName();
        dto.email = user.getEmail();
        dto.phoneNumber = user.getPhoneNumber();
        dto.birthday = user.getBirthday();
        dto.gender = user.getGender();
        dto.profilePicture = user.getProfilePicture();
        dto.status = user.getStatus();
        dto.passwordChangeRequired = user.getPasswordChangeRequired();
        dto.barangay = BarangayDto.from(user.getBarangay());
        return dto;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getRole() { return role; }
    public String getFirstName() { return firstName; }
    public String getMiddleName() { return middleName; }
    public String getLastName() { return lastName; }
    public String getEmail() { return email; }
    public String getPhoneNumber() { return phoneNumber; }
    public LocalDate getBirthday() { return birthday; }
    public String getGender() { return gender; }
    public String getProfilePicture() { return profilePicture; }
    public String getStatus() { return status; }
    public Boolean getPasswordChangeRequired() { return passwordChangeRequired; }
    public BarangayDto getBarangay() { return barangay; }

    /**
     * Nested, minimal barangay projection. Keeps serialization from walking the
     * full barangay graph while preserving the {@code barangay.barangayName}
     * shape the frontend relies on.
     */
    public static class BarangayDto {
        private Long id;
        private String barangayName;
        private String brgyCaptain;
        private String contactNumber;
        private String emailAddress;

        public static BarangayDto from(Barangay barangay) {
            if (barangay == null) {
                return null;
            }
            BarangayDto dto = new BarangayDto();
            dto.id = barangay.getId();
            dto.barangayName = barangay.getBarangayName();
            dto.brgyCaptain = barangay.getBrgyCaptain();
            dto.contactNumber = barangay.getContactNumber();
            dto.emailAddress = barangay.getEmailAddress();
            return dto;
        }

        public Long getId() { return id; }
        public String getBarangayName() { return barangayName; }
        public String getBrgyCaptain() { return brgyCaptain; }
        public String getContactNumber() { return contactNumber; }
        public String getEmailAddress() { return emailAddress; }
    }
}
