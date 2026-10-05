package io.learnk8s.knote;

import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Controller;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ui.Model;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.servlet.view.freemarker.FreeMarkerViewResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.repository.MongoRepository;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.IOException;
import java.io.File;
import java.util.List;
import java.util.Collections;
import java.util.UUID;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;

@SpringBootApplication
public class KnoteJavaApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnoteJavaApplication.class, args);
    }

    @Bean
    FreeMarkerViewResolver freeMarkerViewResolver() {
        FreeMarkerViewResolver resolver = new FreeMarkerViewResolver();
        resolver.setSuffix(".ftl");
        resolver.setContentType("text/html");
        return resolver;
    }

}

@Document(collection = "notes")
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
class Note {

    @Id
    private String id;
    private String description;

    @Override
    public String toString() {
        return description;
    }

}

interface NotesRepository extends MongoRepository<Note, String> {

}

@Controller 
class KNoteController {

    @Autowired
    private NotesRepository notesRepository;

    @Autowired
    private KnoteProperties properties;

    @Autowired
    private MinioClient minioClient;

    private final Parser parser = Parser.builder().build();
    private final HtmlRenderer renderer = HtmlRenderer.builder().build();

    @GetMapping("/")
    public String index(Model model) {
        getAllNotes(model);
        return "index";
    }

    @PostMapping("/note")
    public String saveNotes(@RequestParam("image") MultipartFile file,
                            @RequestParam String description,
                            @RequestParam(required = false) String publish,
                            @RequestParam(required = false) String upload,
                            Model model) throws IOException {

        if (publish != null && publish.equals("Publish")) {
            saveNote(description, model);
            getAllNotes(model);
            return "redirect:/";
        }
        if (upload != null && upload.equals("Upload")
                && file != null && file.getOriginalFilename() != null
                && !file.getOriginalFilename().isEmpty()) {
            uploadImage(file, description, model);
            getAllNotes(model);
        }
        // After save fetch all notes again
        return "index";
    }

    private void uploadImage(MultipartFile file, String description, Model model) throws IOException {
        String originalFilename = new File(file.getOriginalFilename()).getName();
        String extension = "";
        int extensionIndex = originalFilename.lastIndexOf('.');
        if (extensionIndex >= 0) {
            extension = originalFilename.substring(extensionIndex);
        }
        String fileId = UUID.randomUUID() + extension;
        try {
            ensureBucket();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(fileId)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType() == null
                            ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                            : file.getContentType())
                    .build());
        } catch (Exception exception) {
            throw new IOException("Could not upload image to MinIO", exception);
        }
        model.addAttribute("description", description
                + " ![](/uploads/" + fileId + ")");
    }

    @GetMapping("/uploads/{objectName:.+}")
    public ResponseEntity<Resource> getImage(@PathVariable String objectName) throws IOException {
        try {
            var metadata = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectName)
                    .build());
            byte[] content;
            try (var stream = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectName)
                    .build())) {
                content = stream.readAllBytes();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(metadata.contentType()))
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(content.length))
                    .body(new ByteArrayResource(content));
        } catch (Exception exception) {
            throw new IOException("Could not read image from MinIO", exception);
        }
    }

    private void ensureBucket() throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder()
                .bucket(properties.getBucket())
                .build())) {
            minioClient.makeBucket(MakeBucketArgs.builder()
                    .bucket(properties.getBucket())
                    .build());
        }
    }

    private void saveNote(String description, Model model) {
        if (description != null && !description.trim().isEmpty()) {
            Node document = parser.parse(description.trim());
            String html = renderer.render(document);
            notesRepository.save(new Note(null, html));
            //After publish you need to clean up the textarea
            model.addAttribute("description", "");
        }
    }

    private void getAllNotes(Model model) {
        List<Note> notes = notesRepository.findAll();             
        Collections.reverse(notes);
        model.addAttribute("notes", notes);
    }

}

@ConfigurationProperties(prefix = "knote")
class KnoteProperties {

    @Value("${MINIO_ENDPOINT:http://localhost:9000}")
    private String endpoint;

    @Value("${MINIO_ACCESS_KEY:minioadmin}")
    private String accessKey;

    @Value("${MINIO_SECRET_KEY:minioadmin}")
    private String secretKey;

    @Value("${MINIO_BUCKET:knote}")
    private String bucket;

    public String getEndpoint() {
        return endpoint;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public String getBucket() {
        return bucket;
    }
}

@Configuration
@EnableConfigurationProperties(KnoteProperties.class)
class KnoteConfig {

    @Autowired
    private KnoteProperties properties;

    @Bean
    MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }
}