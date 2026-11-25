package com.waterrepair.common;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class IndexController {

    @GetMapping("/")
    public RedirectView index() {
        return new RedirectView("/swagger-ui/index.html");
    }

    /** 客服争议核查页的友好路径别名，统一跳到静态单页。 */
    @GetMapping({"/dispute", "/compensation/dispute"})
    public RedirectView disputePage() {
        return new RedirectView("/dispute.html");
    }
}
