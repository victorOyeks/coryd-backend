package org.coryd.ops.dto;

/**
 * A driver's uploaded file as the review screen needs it: url to show it, downloadUrl for the Download button
 * (served as an attachment), fileType for the PDF / image badge. Null when there is no file - an approved item's file
 * is deleted once approved.
 */
public record FileLink(String url, String downloadUrl, String fileType) {

    public static final String PDF = "PDF";
    public static final String IMAGE = "IMAGE";

    /** Cloudinary keeps PDFs as "raw" files; everything else uploaded is an image. */
    public static FileLink of(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        boolean pdf = url.contains("/raw/upload/") || url.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf");
        String download = !pdf && url.contains("/image/upload/") ? url.replaceFirst("/image/upload/", "/image/upload/fl_attachment/") : url;
        return new FileLink(url, download, pdf ? PDF : IMAGE);
    }
}
