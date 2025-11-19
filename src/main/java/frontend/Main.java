package frontend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// import doda25team8.version.VersionUtil;

@SpringBootApplication
public class Main {

    public static void main(String[] args) {
        // System.out.println("Library version: " + VersionUtil.getVersion());
        SpringApplication.run(Main.class, args);
    }

}