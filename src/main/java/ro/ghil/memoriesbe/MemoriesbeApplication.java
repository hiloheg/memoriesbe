package ro.ghil.memoriesbe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class MemoriesbeApplication {

	public static void main(String[] args) {
		SpringApplication.run(MemoriesbeApplication.class, args);
	}

}
