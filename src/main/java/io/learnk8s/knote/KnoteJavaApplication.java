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
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
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
        File uploadsDir = new File(properties.getUploadDir());
        if (!uploadsDir.exists() && !uploadsDir.mkdirs()) {
            throw new IOException("Could not create upload directory: " + uploadsDir);
        }

        String originalFilename = new File(file.getOriginalFilename()).getName();
        String extension = "";
        int extensionIndex = originalFilename.lastIndexOf('.');
        if (extensionIndex >= 0) {
            extension = originalFilename.substring(extensionIndex);
        }
        String fileId = UUID.randomUUID() + extension;
        file.transferTo(new File(uploadsDir, fileId));
        model.addAttribute("description", description
                + " ![](/uploads/" + fileId + ")");
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

    @Value("${uploadDir:${java.io.tmpdir}/knote-uploads/}")
    private String uploadDir;

    public String getUploadDir() {
        return uploadDir;
    }
}

@Configuration
@EnableConfigurationProperties(KnoteProperties.class)
class KnoteConfig implements WebMvcConfigurer {

    @Autowired
    private KnoteProperties properties;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + properties.getUploadDir())
                .setCachePeriod(3600)
                .resourceChain(true)
                .addResolver(new PathResourceResolver());
    }
}