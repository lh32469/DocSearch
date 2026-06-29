package org.gpc4j.docs.controller;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.gpc4j.docs.model.DocSearchDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import net.ravendb.client.documents.session.IDocumentSession;

/**
 * REST controller that ingests scanned documents (PDFs and images) and stores
 * them in RavenDB for asynchronous AI transcription.
 *
 * <p>Each uploaded file is converted to one or more PNG page images which are
 * stored as RavenDB attachments. The {@code pages} field is left {@code null}
 * so the background transcription tasks pick up the document on their next run.
 */
@RestController
@RequestMapping("/api")
public class DocumentController {

  private static final Logger log = LoggerFactory
    .getLogger(DocumentController.class);

  private static final float RENDER_DPI = 150f;

  /** Prompt sent to the AI vision model for each page image. */
  public static final String OCR_PROMPT = "Read all text from this public "
    + "document page image. "
    + "Return the text content, one line per line, exactly as it appears. "
    + "After the extracted text, add a line: CONFIDENCE: NN% where NN reflects "
    + "how clearly the text was readable in percentage.";

  /**
   * Matches the AI-appended confidence line, e.g. {@code CONFIDENCE: 87%}.
   */
  public static final Pattern CONFIDENCE_PATTERN = Pattern
    .compile("CONFIDENCE:\\s*(\\d+(?:\\.\\d+)?)%", Pattern.CASE_INSENSITIVE);

  private final IDocumentSession session;

  /**
   * Constructs the controller with its required dependencies.
   *
   * @param session request-scoped RavenDB session
   */
  public DocumentController(IDocumentSession session) {

    this.session = session;
  }


  /**
   * Accepts a scanned document (PDF or image), converts it to one or more PNG
   * page images, and stores the {@link DocSearchDoc} in RavenDB with the page
   * images as attachments. No AI transcription is performed at ingest time.
   *
   * <p>Supported file types: {@code .pdf}, {@code .png}, {@code .jpg},
   * {@code .jpeg}, {@code .gif}. PDF pages are rendered at 150 DPI; image
   * files are treated as single-page documents and re-encoded as PNG.
   *
   * @param file the uploaded PDF or image file
   * @return {@code 202 Accepted} on success, or {@code 400} if the filename
   * is absent or the file type is unsupported
   * @throws IOException if reading or rendering fails
   */
  @PostMapping("/stage")
  public ResponseEntity<String> stage(@RequestParam("file") MultipartFile file)
    throws IOException {

    String filename = file.getOriginalFilename();

    if (filename == null || filename.isBlank()) {
      return ResponseEntity.badRequest().body("File must have a filename");
    }

    // Index access required (pageImages.get(i)), so ArrayList is correct here.
    List<byte[]> pageImages = new ArrayList<>();
    String lower = filename.toLowerCase();

    if (lower.endsWith(".pdf")) {
      log.debug("Staging PDF: {}", filename);
      try (PDDocument pdf = Loader.loadPDF(file.getBytes())) {
        PDFRenderer renderer = new PDFRenderer(pdf);
        int pageCount = pdf.getNumberOfPages();
        log.debug("PDF has {} page(s)", pageCount);
        for (int i = 0; i < pageCount; i++) {
          BufferedImage image = renderer
            .renderImageWithDPI(i, RENDER_DPI, ImageType.RGB);
          ByteArrayOutputStream bos = new ByteArrayOutputStream();
          ImageIO.write(image, "PNG", bos);
          pageImages.add(bos.toByteArray());
          log.debug("Page {}: rendered {} bytes", i + 1, bos.size());
        }
      }
    } else if (lower.endsWith(".png") || lower.endsWith(".jpg")
      || lower.endsWith(".jpeg") || lower.endsWith(".gif")) {
      log.debug("Staging image: {}", filename);
      BufferedImage image = ImageIO.read(file.getInputStream());
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      ImageIO.write(image, "PNG", bos);
      pageImages.add(bos.toByteArray());
    } else {
      return ResponseEntity.badRequest().body("Unsupported file type: " + filename);
    }

    String docId = "Doc/" + filename;

    DocSearchDoc doc = new DocSearchDoc();
    doc.setFilename(filename);
    doc.setPageCount(pageImages.size());
    doc.setCreatedAt(Instant.now());

    session.store(doc, docId);

    for (int i = 0; i < pageImages.size(); i++) {
      String attachmentName = "page-" + (i + 1) + ".png";
      session
        .advanced()
        .attachments()
        .store(doc, attachmentName, new ByteArrayInputStream(pageImages.get(i)),
          "image/png");
      log.debug("Queued attachment: {}", attachmentName);
    }

    session.saveChanges();
    log
      .info("Staged '{}' with {} page(s) and {} attachment(s)", filename,
        pageImages.size(), pageImages.size());

    return ResponseEntity.accepted().build();
  }

}
