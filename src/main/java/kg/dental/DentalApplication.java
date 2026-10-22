package kg.dental;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DentalApplication {

	public static void main(String[] args) {
		SpringApplication.run(DentalApplication.class, args);
	}

}
