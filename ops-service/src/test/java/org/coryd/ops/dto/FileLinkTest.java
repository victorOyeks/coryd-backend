package org.coryd.ops.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What the review screen's file badge and Download button get for each kind of upload. */
class FileLinkTest {

    @Test
    void anImageDownloadsAsAnAttachment() {
        FileLink link = FileLink.of("https://res.cloudinary.com/coryd/image/upload/v17/drivers/licence.jpg");
        assertThat(link.fileType()).isEqualTo(FileLink.IMAGE);
        assertThat(link.downloadUrl()).isEqualTo("https://res.cloudinary.com/coryd/image/upload/fl_attachment/v17/drivers/licence.jpg");
        assertThat(link.url()).isEqualTo("https://res.cloudinary.com/coryd/image/upload/v17/drivers/licence.jpg");
    }

    @Test
    void aPdfIsAPdfAndDownloadsAsItIs() {
        assertThat(FileLink.of("https://res.cloudinary.com/coryd/raw/upload/v17/drivers/insurance.pdf"))
                .isEqualTo(new FileLink("https://res.cloudinary.com/coryd/raw/upload/v17/drivers/insurance.pdf",
                        "https://res.cloudinary.com/coryd/raw/upload/v17/drivers/insurance.pdf", FileLink.PDF));
        assertThat(FileLink.of("https://elsewhere.example/doc.PDF").fileType()).isEqualTo(FileLink.PDF);
    }

    @Test
    void noFileIsNoLink() {
        assertThat(FileLink.of(null)).isNull();
        assertThat(FileLink.of(" ")).isNull();
    }

    @Test
    void aNonCloudinaryImageLinkIsLeftAlone() {
        assertThat(FileLink.of("https://img/ext.jpg").downloadUrl()).isEqualTo("https://img/ext.jpg");
    }
}
