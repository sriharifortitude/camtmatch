package io.github.sriharifortitude.camtmatch;

import org.springframework.boot.SpringApplication;

public class TestCamtmatchApplication {

	public static void main(String[] args) {
		SpringApplication.from(CamtmatchApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
