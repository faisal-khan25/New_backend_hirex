package com.hirex.service;


import com.hirex.dto.AtsResultDto;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ATS Scoring Service
 * Compares resume text against a fixed list of required keywords.
 * Score = (matchedKeywords / totalKeywords) * 100
 */
@Service
public class AtsService {

    // Required ATS keywords — 14 total
    private static final List<String> REQUIRED_KEYWORDS = Arrays.asList(
            "Java",
            "Spring Boot",
            "Microservices",
            "REST API",
            "MySQL",
            "Hibernate",
            "JPA",
            "Docker",
            "Git",
            "Maven",
            "React",
            "JavaScript",
            "Postman",
            "AWS"
    );

    private static final int TOTAL_KEYWORDS = REQUIRED_KEYWORDS.size(); // 14

    /**
     * Run ATS check on raw resume text.
     */
    public AtsResultDto check(String resumeText) {
        if (resumeText == null || resumeText.isBlank()) {
            return new AtsResultDto(0,
                    List.of(),
                    new ArrayList<>(REQUIRED_KEYWORDS),
                    List.of("Resume text is empty. Please upload a valid resume."));
        }

        String lower = resumeText.toLowerCase();

        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (String kw : REQUIRED_KEYWORDS) {
            if (lower.contains(kw.toLowerCase())) {
                matched.add(kw);
            } else {
                missing.add(kw);
            }
        }

        // Score = (matched / total) * 100, rounded to nearest integer
        int score = (int) Math.round((matched.size() / (double) TOTAL_KEYWORDS) * 100);

        List<String> suggestions = buildSuggestions(missing, score);

        return new AtsResultDto(score, matched, missing, suggestions);
    }

    private List<String> buildSuggestions(List<String> missing, int score) {
        List<String> suggestions = new ArrayList<>();

        for (String kw : missing) {
            switch (kw) {
                case "Java"         -> suggestions.add("Mention Java proficiency with version details (e.g. Java 17/21).");
                case "Spring Boot"  -> suggestions.add("Highlight Spring Boot projects or REST APIs you have built.");
                case "Microservices"-> suggestions.add("Include any Microservices architecture experience or design patterns used.");
                case "REST API"     -> suggestions.add("Document REST API design and implementation experience explicitly.");
                case "MySQL"        -> suggestions.add("List MySQL as a primary database with schema design examples.");
                case "Hibernate"    -> suggestions.add("Mention Hibernate ORM usage in your project descriptions.");
                case "JPA"          -> suggestions.add("Reference Spring Data JPA / Jakarta Persistence usage.");
                case "Docker"       -> suggestions.add("Add Docker containerisation experience (Dockerfile, docker-compose).");
                case "Git"          -> suggestions.add("Include Git version control workflows (branches, PRs, CI/CD).");
                case "Maven"        -> suggestions.add("Mention Maven as your build tool in project descriptions.");
                case "React"        -> suggestions.add("Add React.js frontend experience with hooks and component design.");
                case "JavaScript"   -> suggestions.add("Highlight JavaScript / ES6+ skills in frontend projects.");
                case "Postman"      -> suggestions.add("Reference API testing with Postman or similar tools.");
                case "AWS"          -> suggestions.add("Mention AWS services used (EC2, S3, RDS, Lambda, etc.).");
                default             -> suggestions.add("Add experience with: " + kw);
            }
        }

        if (score >= 80) {
            suggestions.add(0, "🎉 Excellent resume! Only minor keyword additions needed.");
        } else if (score >= 60) {
            suggestions.add(0, "👍 Good profile. Address the missing keywords to reach the top tier.");
        } else if (score >= 40) {
            suggestions.add(0, "⚠️ Average match. Significantly expand your technical skills section.");
        } else {
            suggestions.add(0, "❌ Low match. Consider adding hands-on projects covering the missing technologies.");
        }

        return suggestions;
    }
}
