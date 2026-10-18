package com.simonrowe.meals;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Makes a photo for a recipe that arrived without one: pasted text, a page read without its
 * picture, or one typed in.
 *
 * <p>Generated rather than borrowed. A stock site's licence (Unsplash's included) is not public
 * domain: it asks for attribution and for the image to be served from its own servers, which a
 * private app that fetches every photo with a bearer token cannot do. A generated image owes
 * nobody anything, and pictures the recipe rather than something near it.
 *
 * <p>Like every other model call here it only proposes: the photo is stored (named by its hash,
 * like any upload) and handed back, and is attached to a recipe only when the owner saves it.
 */
@Component
public class MealPhotoGenerator {

  /** Same model, size and quality as the blog's images: about $0.04 a photo. */
  static final String IMAGE_MODEL = "gpt-image-2";
  static final Duration CALL_TIMEOUT = Duration.ofSeconds(90);
  static final int MAX_INGREDIENTS = 12;
  private static final int WIDTH = 1536;
  private static final int HEIGHT = 1024;
  private static final float JPEG_QUALITY = 0.86f;
  private static final Logger LOG = LoggerFactory.getLogger(MealPhotoGenerator.class);
  private static final ExecutorService CALLS = Executors.newVirtualThreadPerTaskExecutor();

  private final ImageModel imageModel;
  private final MealImageStore images;

  public MealPhotoGenerator(final ImageModel imageModel, final MealImageStore images) {
    this.imageModel = imageModel;
    this.images = images;
  }

  /**
   * Generates and stores a photo of a dish.
   *
   * @param name the recipe's name
   * @param ingredients its ingredient names, most important first
   * @return the stored photo's name
   */
  public String generate(final String name, final List<String> ingredients) {
    if (name == null || name.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name the recipe first");
    }
    String prompt = prompt(name, ingredients);
    Future<ImageResponse> call = CALLS.submit(() -> imageModel.call(new ImagePrompt(prompt,
        OpenAiImageOptions.builder().model(IMAGE_MODEL).width(WIDTH).height(HEIGHT)
            .quality("medium").build())));
    ImageResponse response;
    try {
      response = call.get(CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      call.cancel(true);
      throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
          "Making the photo took too long. Try again.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted");
    } catch (ExecutionException e) {
      LOG.warn("Meal photo generation failed: {}", e.getCause().getMessage());
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not make a photo");
    }
    byte[] png = decode(response);
    return images.store(asJpeg(png)).orElseThrow(() -> new ResponseStatusException(
        HttpStatus.BAD_GATEWAY, "The photo came back unreadable"));
  }

  static String prompt(final String name, final List<String> ingredients) {
    String with = ingredients == null ? "" : ingredients.stream()
        .filter(Objects::nonNull).map(String::strip).filter(s -> !s.isEmpty()).distinct()
        .limit(MAX_INGREDIENTS).collect(Collectors.joining(", "));
    return """
        An appetising, natural-light food photograph of a home-cooked dish: %s.%s \
        Plated simply on a ceramic plate or bowl on a warm wooden kitchen table, shot from \
        a 45 degree angle with a shallow depth of field, like a photo in a modern cookbook. \
        Realistic portion for one. No text, no words, no logos, no people, no hands."""
        .formatted(name.strip(), with.isEmpty() ? "" : " Made with " + with + ".");
  }

  // Null checks on the SDK's response are deliberate: the image API has changed shape between
  // models (DALL-E returned a URL, gpt-image returns base64), and the check costs nothing.
  @SuppressWarnings("java:S2583")
  private static byte[] decode(final ImageResponse response) {
    Image output = response == null || response.getResult() == null ? null
        : response.getResult().getOutput();
    String b64 = output == null ? null : output.getB64Json();
    if (b64 == null || b64.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No photo came back");
    }
    return Base64.getDecoder().decode(b64);
  }

  /**
   * The model only returns PNG, about 2.5 MB at this size; as a JPEG it is a tenth of that,
   * which matters on a phone loading a page of cards. Falls back to the PNG if it cannot be
   * read, so a photo is never lost to the conversion.
   */
  static byte[] asJpeg(final byte[] png) {
    try {
      BufferedImage source = ImageIO.read(new ByteArrayInputStream(png));
      if (source == null) {
        return png;
      }
      BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(),
          BufferedImage.TYPE_INT_RGB);
      Graphics2D g = rgb.createGraphics();
      g.drawImage(source, 0, 0, Color.WHITE, null);
      g.dispose();
      ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
      ImageWriteParam param = writer.getDefaultWriteParam();
      param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      param.setCompressionQuality(JPEG_QUALITY);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
        writer.setOutput(stream);
        writer.write(null, new IIOImage(rgb, null, null), param);
      } finally {
        writer.dispose();
      }
      return out.toByteArray();
    } catch (IOException e) {
      return png;
    }
  }
}
