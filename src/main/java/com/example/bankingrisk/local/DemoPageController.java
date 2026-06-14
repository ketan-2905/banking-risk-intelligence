package com.example.bankingrisk.local;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;

@Controller
@Profile("local")
class DemoPageController {

    @GetMapping("/demo")
    public void demo(HttpServletResponse response) throws IOException {
        response.sendRedirect("/demo/index.html");
    }
}
