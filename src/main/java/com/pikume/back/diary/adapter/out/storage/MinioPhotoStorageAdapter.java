package com.pikume.back.diary.adapter.out.storage;

import com.pikume.back.diary.adapter.out.cache.ImageCacheProperties;
import com.pikume.back.diary.application.dto.DiaryPhotoUpload;
import com.pikume.back.diary.application.port.out.LoadDiaryPhotoObjectPort;
import com.pikume.back.diary.application.port.out.RelocateDiaryPhotoPort;
import com.pikume.back.diary.application.port.out.ResolveDiaryPhotoUrlPort;
import com.pikume.back.diary.application.port.out.StoreDiaryPhotoPort;
import com.pikume.back.diary.application.port.out.StoreOptimizedDiaryPhotoPort;
import com.pikume.back.diary.domain.vo.DiaryPhotoType;
import com.pikume.back.diary.domain.vo.DiaryVisibility;
import com.pikume.back.global.storage.StorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

import static com.pikume.back.diary.adapter.out.storage.PhotoObjectKeyConstants.PUBLIC_PREFIX;

@Slf4j
@Component
public class MinioPhotoStorageAdapter implements StoreDiaryPhotoPort, RelocateDiaryPhotoPort,
		ResolveDiaryPhotoUrlPort, LoadDiaryPhotoObjectPort, StoreOptimizedDiaryPhotoPort {

	private final S3Client s3Client;
	private final PhotoUtil photoUtil;
	private final StorageProperties storageProperties;
	private final ImageCacheProperties imageCacheProperties;

	public MinioPhotoStorageAdapter(S3Client s3Client, PhotoUtil photoUtil,
			StorageProperties storageProperties, ImageCacheProperties imageCacheProperties) {
		this.s3Client = s3Client;
		this.photoUtil = photoUtil;
		this.storageProperties = storageProperties;
		this.imageCacheProperties = imageCacheProperties;
	}

	@Override
	public String store(DiaryPhotoUpload photo, DiaryVisibility visibility) {
		try {
			byte[] bytes = photo.bytes();
			if (bytes.length == 0) {
				throw new IllegalArgumentException("빈 사진은 저장할 수 없습니다.");
			}
			String objectKey = photoUtil.generateDiaryUserImageObjectKey(
					visibility.isPublicStorageScope(),
					photo.originalFilename());
			ensureBucketExists(storageProperties.getBucket());
			PutObjectRequest request = PutObjectRequest.builder()
					.bucket(storageProperties.getBucket())
					.key(objectKey)
					.contentType(photo.contentType())
					.cacheControl(cacheControlFor(objectKey))
					.contentLength((long) bytes.length)
					.build();
			s3Client.putObject(request, RequestBody.fromBytes(bytes));
			return objectKey;
		} catch (RuntimeException exception) {
			throw new RuntimeException("일기 사진 저장 중 오류가 발생했습니다.", exception);
		}
	}

	public byte[] loadObject(String objectKey) {
		try {
			GetObjectRequest request = GetObjectRequest.builder()
					.bucket(storageProperties.getBucket())
					.key(objectKey)
					.build();

			ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(request);
			return objectBytes.asByteArray();
		} catch (NoSuchKeyException e) {
			throw new RuntimeException("스토리지 객체를 찾을 수 없습니다: " + objectKey, e);
		} catch (S3Exception e) {
			if (e.statusCode() == 404) {
				throw new RuntimeException("스토리지 객체를 찾을 수 없습니다: " + objectKey, e);
			}
			throw new RuntimeException("스토리지 객체를 읽는 중 오류가 발생했습니다.", e);
		}
	}

	@Override
	public byte[] load(String objectKey) {
		return loadObject(objectKey);
	}

	@Override
	public void store(String objectKey, String contentType, byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			throw new IllegalArgumentException("빈 최적화 이미지는 저장할 수 없습니다.");
		}
		ensureBucketExists(storageProperties.getBucket());
		PutObjectRequest request = PutObjectRequest.builder()
				.bucket(storageProperties.getBucket())
				.key(objectKey)
				.contentType(contentType)
				.cacheControl(cacheControlFor(objectKey))
				.contentLength((long) bytes.length)
				.build();
		s3Client.putObject(request, RequestBody.fromBytes(bytes));
	}

	private void ensureBucketExists(String bucketName) {
		try {
			HeadBucketRequest headBucketRequest = HeadBucketRequest.builder()
					.bucket(bucketName)
					.build();
			s3Client.headBucket(headBucketRequest);
		} catch (S3Exception e) {
			if (e.statusCode() == 404) {
				CreateBucketRequest createBucketRequest = CreateBucketRequest.builder()
						.bucket(bucketName)
						.build();
				s3Client.createBucket(createBucketRequest);
				log.info("event=storage_bucket_created outcome=success");
			} else {
				throw e;
			}
		}
	}

	public String getMinIOStoragePhotoUrl(String objectName, boolean isPublic) {
		String clientToS3BaseUrl = storageProperties.clientToS3BaseUrl();
		if (isPublic) {
			return clientToS3BaseUrl + "/" + storageProperties.getBucket() + "/" + objectName;
		}

		S3Presigner.Builder presignerBuilder = S3Presigner.builder()
				.endpointOverride(URI.create(clientToS3BaseUrl))
				.region(Region.of(storageProperties.getRegion()))
				.credentialsProvider(
						StaticCredentialsProvider.create(
								AwsBasicCredentials.create(
										storageProperties.getAccessKey(),
										storageProperties.getSecretKey())))
				.serviceConfiguration(
						S3Configuration.builder()
								.pathStyleAccessEnabled(true)
								.build());

		try (S3Presigner presigner = presignerBuilder.build()) {
			GetObjectRequest getObjectRequest = GetObjectRequest.builder()
					.bucket(storageProperties.getBucket())
					.key(objectName)
					.build();

			GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
					.signatureDuration(Duration.ofMinutes(30))
					.getObjectRequest(getObjectRequest)
					.build();

			return presigner.presignGetObject(presignRequest).url().toString();
		}
	}

	public String getPhotoUrl(String objectName, boolean isPublic) {
		if (objectName == null || objectName.isBlank()) {
			return null;
		}
		boolean publicObject = isPublicObjectKey(objectName);
		String storageType = storageProperties.getType();
		if ("minio".equalsIgnoreCase(storageType)) {
			try {
				return getMinIOStoragePhotoUrl(objectName, publicObject);
			} catch (Exception e) {
				log.error("event=storage_photo_url_resolution_failed outcome=failed provider=minio exception={}",
						e.getClass().getSimpleName());
				return null;
			}
		}

		S3Presigner.Builder presignerBuilder = S3Presigner.builder()
				.region(Region.of(storageProperties.getRegion()));

		try (S3Presigner presigner = presignerBuilder.build()) {
			GetObjectRequest getObjectRequest = GetObjectRequest.builder()
					.bucket(storageProperties.getBucket())
					.key(objectName)
					.build();

			GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
					.signatureDuration(Duration.ofHours(12))
					.getObjectRequest(getObjectRequest)
					.build();

			return presigner.presignGetObject(presignRequest).url().toString();
		} catch (Exception e) {
			log.error("event=storage_photo_url_resolution_failed outcome=failed provider=s3 exception={}",
					e.getClass().getSimpleName());
			return null;
		}
	}

	@Override
	public String resolve(String objectKey) {
		return getPhotoUrl(objectKey, isPublicObjectKey(objectKey));
	}

	public String saveGeneratedImage(String base64Data, String userId, String fileExtension) {
		try {
			if (base64Data == null || base64Data.trim().isEmpty()) {
				throw new IllegalArgumentException("Base64 데이터가 비어있습니다.");
			}

			String cleanExtension = cleanExtension(fileExtension);
			byte[] imageBytes = Base64.getDecoder().decode(base64Data);

			String objectName = photoUtil.generateDiaryAiImageObjectKey(cleanExtension);

			ensureBucketExists(storageProperties.getBucket());

			PutObjectRequest putObjectRequest = PutObjectRequest.builder()
					.bucket(storageProperties.getBucket())
					.key(objectName)
					.contentType(contentTypeForExtension(cleanExtension))
					.cacheControl(cacheControlFor(objectName))
					.build();

			s3Client.putObject(putObjectRequest, RequestBody.fromBytes(imageBytes));

			log.debug("event=generated_image_stored outcome=success userId={} sizeBytes={}", userId, imageBytes.length);
			return objectName;

		} catch (IllegalArgumentException e) {
			throw new RuntimeException("Base64 데이터가 올바르지 않습니다.", e);
		} catch (Exception e) {
			throw new RuntimeException("AI 이미지 저장 중 오류가 발생했습니다.", e);
		}
	}

	private String cleanExtension(String fileExtension) {
		return fileExtension.startsWith(".") ? fileExtension.substring(1) : fileExtension;
	}

	private String contentTypeForExtension(String extension) {
		return switch (extension.toLowerCase()) {
			case "jpg", "jpeg" -> "image/jpeg";
			case "png" -> "image/png";
			case "gif" -> "image/gif";
			case "webp" -> "image/webp";
			case "bmp" -> "image/bmp";
			case "svg" -> "image/svg+xml";
			default -> "application/octet-stream";
		};
	}

	public String moveToPublic(String sourceKey) {
		String targetKey = photoUtil.publicObjectKeyFor(sourceKey);
		return copyObject(sourceKey, targetKey, true);
	}

	@Override
	public String copyToVisibilityScope(String sourceKey, DiaryVisibility visibility, DiaryPhotoType sourceType) {
		String targetKey = photoUtil.visibilityObjectKeyFor(sourceKey, isPublicDiary(visibility), sourceType);
		return copyObject(sourceKey, targetKey, false);
	}

	private String copyObject(String sourceKey, String targetKey, boolean deleteSource) {
		boolean fileCopied = false;

		try {
			if (sourceKey.equals(targetKey)) {
				log.debug("event=storage_object_copy outcome=skipped reason=same_key");
				return sourceKey;
			}

			if (objectHead(targetKey).isPresent()) {
				log.debug("event=storage_object_copy outcome=skipped reason=target_exists");
				if (deleteSource) {
					deleteObject(sourceKey);
				}
				return targetKey;
			}

			HeadObjectResponse sourceHead = objectHead(sourceKey)
					.orElseThrow(() -> {
						return new RuntimeException("소스 파일을 찾을 수 없습니다: " + sourceKey);
					});

			CopyObjectRequest.Builder copyRequestBuilder = CopyObjectRequest.builder()
					.sourceBucket(storageProperties.getBucket())
					.sourceKey(sourceKey)
					.destinationBucket(storageProperties.getBucket())
					.destinationKey(targetKey)
					.metadataDirective(MetadataDirective.REPLACE)
					.cacheControl(cacheControlFor(targetKey));

			if (hasText(sourceHead.contentType())) {
				copyRequestBuilder.contentType(sourceHead.contentType());
			}
			if (sourceHead.metadata() != null && !sourceHead.metadata().isEmpty()) {
				copyRequestBuilder.metadata(sourceHead.metadata());
			}

			s3Client.copyObject(copyRequestBuilder.build());
			fileCopied = true;

			if (!objectExists(targetKey)) {
				throw new RuntimeException("파일 복사 후 확인 실패: " + targetKey);
			}

			if (deleteSource) {
				deleteObject(sourceKey);
			}

			log.debug("event=storage_object_copied outcome=success sourceDeleted={}", deleteSource);
			return targetKey;

		} catch (S3Exception e) {
			if (fileCopied) {
				try {
					if (!objectExists(targetKey)) {
						throw new RuntimeException("복사된 파일을 확인할 수 없습니다: " + targetKey);
					}
					deleteObject(targetKey);
					log.debug("event=storage_object_copy_rollback outcome=success");
				} catch (Exception rollbackException) {
					log.error("event=storage_object_copy_rollback outcome=failed exception={}",
							rollbackException.getClass().getSimpleName());
				}
			}

			throw new RuntimeException("파일 복제 중 오류가 발생했습니다.", e);
		} catch (Exception e) {
			if (fileCopied) {
				try {
					if (!objectExists(targetKey)) {
						throw new RuntimeException("복사된 파일을 확인할 수 없습니다: " + targetKey);
					}
					deleteObject(targetKey);
					log.debug("event=storage_object_copy_rollback outcome=success");
				} catch (Exception rollbackException) {
					log.error("event=storage_object_copy_rollback outcome=failed exception={}",
							rollbackException.getClass().getSimpleName());
				}
			}

			throw new RuntimeException("파일 복제 중 오류가 발생했습니다.", e);
		}
	}

	private boolean isPublicDiary(DiaryVisibility visibility) {
		return visibility == DiaryVisibility.PUBLIC || visibility == DiaryVisibility.ANONYMOUS;
	}

	private boolean isPublicObjectKey(String objectName) {
		return objectName.startsWith(PUBLIC_PREFIX);
	}

	private boolean objectExists(String key) {
		return objectHead(key).isPresent();
	}

	private Optional<HeadObjectResponse> objectHead(String key) {
		try {
			HeadObjectRequest request = HeadObjectRequest.builder()
					.bucket(storageProperties.getBucket())
					.key(key)
					.build();

			return Optional.of(s3Client.headObject(request));
		} catch (NoSuchKeyException e) {
			return Optional.empty();
		} catch (S3Exception e) {
			if (e.statusCode() == 404) {
				return Optional.empty();
			}
			throw e;
		}
	}

	private String cacheControlFor(String objectKey) {
		return imageCacheProperties.cacheControlForObjectKey(objectKey);
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	public void deleteObject(String key) {
		try {
			DeleteObjectRequest request = DeleteObjectRequest.builder()
					.bucket(storageProperties.getBucket())
					.key(key)
					.build();

			s3Client.deleteObject(request);
			log.debug("event=storage_object_deleted outcome=success");
		} catch (S3Exception e) {
			throw e;
		}
	}

	@Override
	public void delete(String objectKey) {
		deleteObject(objectKey);
	}
}
