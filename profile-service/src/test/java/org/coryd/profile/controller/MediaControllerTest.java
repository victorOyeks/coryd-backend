package org.coryd.profile.controller;

import org.coryd.common.enums.UserRole;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.profile.dto.MediaUploadResponse;
import org.coryd.profile.entity.MediaUpload;
import org.coryd.profile.repository.MediaUploadRepository;
import org.coryd.profile.service.CloudinaryService;
import org.coryd.profile.service.MediaOwnership;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Uploads are recorded against the uploader; only they can delete one, and nobody else can submit it as theirs. */
class MediaControllerTest {

    private final CloudinaryService cloudinary = mock(CloudinaryService.class);
    private final MediaUploadRepository uploads = mock(MediaUploadRepository.class);
    private final MediaOwnership ownership = new MediaOwnership(uploads);
    private final MediaController controller = new MediaController(cloudinary, ownership);

    private static CoRydUserDetails user(String id) {
        CoRydUserDetails details = mock(CoRydUserDetails.class);
        when(details.getUserId()).thenReturn(id);
        when(details.getActiveRole()).thenReturn(UserRole.RIDER);
        return details;
    }

    private void uploadedBy(String publicId, String userId) {
        when(uploads.findById(publicId)).thenReturn(Optional.of(
                MediaUpload.builder().publicId(publicId).userId(userId).uploadedAt(Instant.now()).build()));
    }

    @Test
    void anUploadIsRecordedAgainstTheUploader() {
        when(cloudinary.upload(any())).thenReturn(new MediaUploadResponse("https://img/x.png", "coryd/uploads/x"));

        controller.upload(user("rider-1"), new MockMultipartFile("file", "x.png", "image/png", new byte[]{1}));

        ArgumentCaptor<MediaUpload> saved = ArgumentCaptor.forClass(MediaUpload.class);
        verify(uploads).save(saved.capture());
        assertThat(saved.getValue().getPublicId()).isEqualTo("coryd/uploads/x");
        assertThat(saved.getValue().getUserId()).isEqualTo("rider-1");
    }

    @Test
    void theUploaderCanDeleteTheirOwnFile() {
        uploadedBy("coryd/uploads/x", "rider-1");

        controller.delete(user("rider-1"), "coryd/uploads/x", null);

        verify(cloudinary).delete("coryd/uploads/x", null);
    }

    @Test
    void someoneElsesFileCannotBeDeleted() {
        uploadedBy("coryd/uploads/x", "rider-1");

        assertThatThrownBy(() -> controller.delete(user("rider-2"), "coryd/uploads/x", null))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(cloudinary, never()).delete(anyString(), any());
    }

    @Test
    void aFileWithNoRecordCannotBeDeleted() {
        when(uploads.findById("coryd/uploads/licence")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.delete(user("rider-2"), "coryd/uploads/licence", null))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(cloudinary, never()).delete(anyString(), any());
    }

    @Test
    void someoneElsesUploadCannotBeSubmittedAsYours() {
        uploadedBy("coryd/uploads/x", "rider-1");
        when(uploads.findById("coryd/uploads/old")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ownership.refuseOthersUpload("coryd/uploads/x", "driver-2"))
                .isInstanceOf(BadRequestException.class);
        assertThatCode(() -> ownership.refuseOthersUpload("coryd/uploads/x", "rider-1")).doesNotThrowAnyException();
        assertThatCode(() -> ownership.refuseOthersUpload("coryd/uploads/old", "driver-2")).doesNotThrowAnyException();
    }
}
