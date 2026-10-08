package com.roadwise.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class SupabaseStorageService {

    @Value("${supabase.url}")
    private String supabaseUrl;

    @Value("${supabase.service-key}")
    private String serviceKey;

    @Value("${supabase.bucket-name}")
    private String bucketName;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList(".jpg", ".jpeg", ".png", ".webp");
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB
    private static final int MAX_IMAGE_DIMENSION = 4096;

    public String computeSha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return "";
        }
    }

    public String uploadImage(MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File cannot be empty.");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File size exceeds maximum allowed limit of 5MB.");
        }

        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        boolean validExtension = ALLOWED_EXTENSIONS.stream().anyMatch(originalName::endsWith);
        if (!validExtension) {
            throw new IllegalArgumentException("Invalid image file extension. Allowed formats: JPG, JPEG, PNG, WEBP.");
        }

        byte[] fileBytes = file.getBytes();
        String detectedMimeType = detectMimeType(fileBytes);
        if (!detectedMimeType.startsWith("image/")) {
            throw new IllegalArgumentException("Uploaded file is not a valid image format.");
        }

        // Validate image dimensions for decompression bomb protection
        try (InputStream is = new ByteArrayInputStream(fileBytes)) {
            BufferedImage img = ImageIO.read(is);
            if (img != null) {
                if (img.getWidth() > MAX_IMAGE_DIMENSION || img.getHeight() > MAX_IMAGE_DIMENSION) {
                    throw new IllegalArgumentException("Image dimensions exceed maximum allowed limit of " + MAX_IMAGE_DIMENSION + "x" + MAX_IMAGE_DIMENSION);
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception ignored) {}

        String cleanFileName = UUID.randomUUID() + "_" + originalName.replaceAll("[^a-zA-Z0-9._-]", "_");
        String uploadEndpoint = String.format("%s/storage/v1/object/%s/%s", supabaseUrl, bucketName, cleanFileName);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uploadEndpoint))
                .header("Authorization", "Bearer " + serviceKey)
                .header("Content-Type", detectedMimeType)
                .header("x-upsert", "true")
                .POST(HttpRequest.BodyPublishers.ofByteArray(fileBytes))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new RuntimeException("Supabase upload failed [" + response.statusCode() + "]: " + response.body());
        }

        return String.format("%s/storage/v1/object/public/%s/%s", supabaseUrl, bucketName, cleanFileName);
    }

    private String detectMimeType(byte[] bytes) {
        if (bytes.length >= 3 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF) {
            return "image/jpeg";
        }
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == (byte) 0x50 && bytes[2] == (byte) 0x4E && bytes[3] == (byte) 0x47) {
            return "image/png";
        }
        if (bytes.length >= 12 && bytes[0] == (byte) 'R' && bytes[1] == (byte) 'I' && bytes[2] == (byte) 'F' && bytes[3] == (byte) 'F'
                && bytes[8] == (byte) 'W' && bytes[9] == (byte) 'E' && bytes[10] == (byte) 'B' && bytes[11] == (byte) 'P') {
            return "image/webp";
        }
        return "application/octet-stream";
    }
}
