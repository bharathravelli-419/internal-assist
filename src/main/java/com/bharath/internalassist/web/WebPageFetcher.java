package com.bharath.internalassist.web;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class WebPageFetcher {

    public record Page(String url, String title, String text){}

    private final Set<String> allowedHosts;

    public WebPageFetcher(@Value("${app.web.allowed-hosts}") List<String> allowedHosts){
        this.allowedHosts = allowedHosts.stream()
                .map(h-> h.trim().toLowerCase(Locale.ROOT))
                .filter(h-> !h.isEmpty())
                .collect(Collectors.toSet());
    }

    public Page fetch(String url){
        requireAllowed(url);
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("internal-assist/0.1 (internal research tool)")
                    .timeout(15_0000)
                    .maxBodySize(2_000_000)
                    .get();

            requireAllowed(doc.location());

            doc.select("script, style, noscript, nav, header, footer, aside, form, iframe").remove();
            Element main = doc.selectFirst("main");
            String text = (main != null ? main.text(): doc.body().text());
            return new Page(doc.location(), doc.title(), text.trim());
        }catch (IOException e){
            throw new IllegalStateException("Could not download "+ url +": "+ e.getMessage());
        }
    }
    private void requireAllowed(String url){
        String host;
        try{
            host = URI.create(url).getHost();

        }catch (IllegalArgumentException e){
            host = null;
        }
        if(host == null || !url.toLowerCase(Locale.ROOT).startsWith("https://") || !allowedHosts.contains(host.toLowerCase(Locale.ROOT))){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL is not allowed. Must be https and in the allowed hosts. "+ url);
        }
    }
}
