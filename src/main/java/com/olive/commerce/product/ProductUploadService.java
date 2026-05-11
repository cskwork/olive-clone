package com.olive.commerce.product;

import com.olive.commerce.common.config.AwsS3Properties;
import com.olive.commerce.common.error.BusinessException;
import com.olive.commerce.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.UUID;

/**
 * 상품 이미지 업로드 서비스.
 * S3 presigned URL을 생성하여 Admin UI가 직접 업로드하도록 한다.
 */
@Service
public class ProductUploadService {

    private static final Logger log = LoggerFactory.getLogger(ProductUploadService.class);
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final long PRESIGNED_URL_EXPIRATION_MINUTES = 15;

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final AwsS3Properties properties;

    public ProductUploadService(S3Client s3Client, S3Presigner s3Presigner, AwsS3Properties properties) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.properties = properties;
    }

    /**
     * Presigned PUT URL을 생성한다.
     *
     * @param request 파일명, 크기, Content-Type
     * @return {uploadUrl, fileUrl}
     */
    public ProductDtos.PresignedUrlResponse getPresignedUrl(ProductDtos.PresignedUrlRequest request) {
        // 파일 크기 검증
        if (request.fileSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED,
                String.format("파일 크기는 %d bytes 이하여야 합니다: %d", MAX_FILE_SIZE, request.fileSize()));
        }

        // Content-Type 검증 (이미지 파일만 허용)
        if (!request.contentType().startsWith("image/")) {
            throw new BusinessException(ErrorCode.INVALID_FILE_TYPE,
                "이미지 파일만 업로드 가능합니다: " + request.contentType());
        }

        // 파일 경로 생성: products/{uuid}/{original_filename}
        String uuid = UUID.randomUUID().toString();
        String originalFilename = sanitizeFilename(request.filename());
        String objectKey = "products/" + uuid + "/" + originalFilename;
        String fileUrl = buildFileUrl(objectKey);

        // Presigned URL 생성 (AWS SDK v2 with S3Presigner)
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
            .bucket(properties.bucket())
            .key(objectKey)
            .contentType(request.contentType())
            .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
            .putObjectRequest(putObjectRequest)
            .signatureDuration(Duration.ofMinutes(PRESIGNED_URL_EXPIRATION_MINUTES))
            .build();

        java.net.URI uploadUrl;
        try {
            uploadUrl = s3Presigner.presignPutObject(presignRequest).url().toURI();
        } catch (java.net.URISyntaxException e) {
            log.error("Failed to generate presigned URL", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Presigned URL 생성 실패");
        }

        log.info("Generated presigned URL for file upload: key={}, size={}, type={}",
            objectKey, request.fileSize(), request.contentType());

        return new ProductDtos.PresignedUrlResponse(uploadUrl.toString(), fileUrl);
    }

    private String sanitizeFilename(String filename) {
        // 경로 순회 방지: ../, ..\\ 제거
        String sanitized = filename.replaceAll("[.]{2,}[\\\\/]", "");
        // 슬래시만 제거하고 나머지는 유지
        return sanitized.replaceAll("[/\\\\]", "_");
    }

    private String buildFileUrl(String objectKey) {
        // LocalStack vs S3 URL 구성
        String endpoint = properties.endpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            // LocalStack
            return endpoint + "/" + properties.bucket() + "/" + objectKey;
        }
        // 운영 S3: https://{bucket}.s3.{region}.amazonaws.com/{key}
        return "https://" + properties.bucket() + ".s3." + properties.region() + ".amazonaws.com/" + objectKey;
    }
}
