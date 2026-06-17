package com.hirex.service;


import com.hirex.dto.AtsBulkResponseDto;
import com.hirex.dto.AtsBulkResultDto;
import com.hirex.dto.AtsResultDto;
import com.hirex.dto.AtsSummaryDto;
import com.hirex.entity.*;
import com.hirex.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AtsBulkService
 *
 * Handles bulk ATS analysis across all uploaded resumes.
 * Processes in configurable batches to handle 1000+ resumes
 * without OOM issues.
 *
 * Score → Status mapping:
 *   >= 80  →  HIRED
 *   >= 60  →  SHORTLISTED
 *   <  60  →  REJECTED
 */
@Service
public class AtsBulkService {

    private static final int BATCH_SIZE = 50;   // resumes per batch

    private final ResumeRepository       resumeRepo;
    private final ApplicationRepository  appRepo;
    private final UserRepository         userRepo;
    private final AtsService             atsService;

    public AtsBulkService(ResumeRepository resumeRepo,
                          ApplicationRepository appRepo,
                          UserRepository userRepo,
                          AtsService atsService) {
        this.resumeRepo  = resumeRepo;
        this.appRepo     = appRepo;
        this.userRepo    = userRepo;
        this.atsService  = atsService;
    }

    // ─────────────────────────────────────────────────────────────────────
    // POST /api/ats/analyze-all
    // Scores every resume but does NOT change application statuses.
    // ─────────────────────────────────────────────────────────────────────
    public AtsBulkResponseDto analyzeAll() {
        List<Resume> allResumes = resumeRepo.findAllWithUser();
        return processBatches(allResumes, false);
    }

    // ─────────────────────────────────────────────────────────────────────
    // POST /api/ats/process-all
    // Scores every resume AND updates application statuses in the DB.
    // ─────────────────────────────────────────────────────────────────────
    @Transactional
    public AtsBulkResponseDto processAll() {
        List<Resume> allResumes = resumeRepo.findAllWithUser();
        return processBatches(allResumes, true);
    }

    // ─────────────────────────────────────────────────────────────────────
    // POST /api/ats/analyze-all/job/{jobId}
    // Scores only resumes for candidates who applied to a specific job.
    // ─────────────────────────────────────────────────────────────────────
    public AtsBulkResponseDto analyzeForJob(Long jobId) {
        List<Application> applications = appRepo.findByJob(
                appRepo.findById(jobId).map(Application::getJob)
                        .orElseThrow(() -> new RuntimeException("Job not found"))
        );
        List<Resume> resumes = resumesForApplications(applications);
        return processBatches(resumes, false);
    }

    // ─────────────────────────────────────────────────────────────────────
    // GET /api/ats/summary
    // ─────────────────────────────────────────────────────────────────────
    public AtsSummaryDto getSummary() {
        long total       = appRepo.count();
        long hired       = appRepo.countByStatus(ApplicationStatus.HIRED);
        long shortlisted = appRepo.countByStatus(ApplicationStatus.SHORTLISTED);
        long rejected    = appRepo.countByStatus(ApplicationStatus.REJECTED);
        long pending     = appRepo.countByStatus(ApplicationStatus.APPLIED);
        long withResume  = resumeRepo.count();

        return new AtsSummaryDto(total, hired, shortlisted, rejected, pending, withResume);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Core batch processor.
     * Splits resumeList into chunks of BATCH_SIZE, scores each, and
     * optionally persists the derived status to all applications
     * belonging to that candidate.
     */
    @Transactional
    protected AtsBulkResponseDto processBatches(List<Resume> allResumes, boolean persistStatus) {

        List<AtsBulkResultDto> results    = new ArrayList<>();
        int totalProcessed = 0;
        int totalSkipped   = 0;
        int hiredCount     = 0;
        int shortlistCount = 0;
        int rejectedCount  = 0;

        // Split into batches
        int total = allResumes.size();
        for (int i = 0; i < total; i += BATCH_SIZE) {
            List<Resume> batch = allResumes.subList(i, Math.min(i + BATCH_SIZE, total));

            for (Resume resume : batch) {
                AtsBulkResultDto row = new AtsBulkResultDto();
                row.setResumeId(resume.getId());
                row.setUserId(resume.getUser().getId());
                row.setCandidateName(resume.getUser().getName());
                row.setCandidateEmail(resume.getUser().getEmail());
                row.setFileName(resume.getFileName());

                String text = resume.getResumeText();
                if (text == null || text.isBlank()) {
                    // Cannot analyse – no extracted text
                    row.setAtsScore(0);
                    row.setMatchPercentage(0);
                    row.setStatus("REJECTED");
                    row.setProcessed(false);
                    totalSkipped++;
                } else {
                    AtsResultDto atsResult = atsService.check(text);
                    int score = atsResult.getAtsScore();

                    String derivedStatus = deriveStatus(score);

                    row.setAtsScore(score);
                    row.setMatchPercentage(score);   // same value, different label in UI
                    row.setStatus(derivedStatus);
                    row.setProcessed(true);
                    totalProcessed++;

                    switch (derivedStatus) {
                        case "HIRED"       -> hiredCount++;
                        case "SHORTLISTED" -> shortlistCount++;
                        default            -> rejectedCount++;
                    }

                    // Persist status to ALL applications for this user
                    if (persistStatus) {
                        List<Application> apps = appRepo.findByApplicantId(resume.getUser().getId());
                        ApplicationStatus statusEnum = ApplicationStatus.valueOf(derivedStatus);
                        for (Application app : apps) {
                            app.setStatus(statusEnum);
                        }
                        if (!apps.isEmpty()) {
                            appRepo.saveAll(apps);
                            row.setApplicationId(apps.get(0).getId());
                        }
                    }
                }
                results.add(row);
            }
        }

        AtsBulkResponseDto response = new AtsBulkResponseDto();
        response.setTotalProcessed(totalProcessed);
        response.setTotalSkipped(totalSkipped);
        response.setTotalHired(hiredCount);
        response.setTotalShortlisted(shortlistCount);
        response.setTotalRejected(rejectedCount);
        response.setResults(results);
        response.setMessage(buildMessage(totalProcessed, totalSkipped, persistStatus));
        return response;
    }

    /**
     * Derive application status from ATS score.
     *   >= 80  → HIRED
     *   >= 60  → SHORTLISTED
     *   <  60  → REJECTED
     */
    public static String deriveStatus(int score) {
        if (score >= 80) return "HIRED";
        if (score >= 60) return "SHORTLISTED";
        return "REJECTED";
    }

    private String buildMessage(int processed, int skipped, boolean persisted) {
        String base = String.format(
                "Analysed %d resume%s.", processed, processed == 1 ? "" : "s"
        );
        if (skipped > 0) {
            base += String.format(" %d skipped (no text extracted).", skipped);
        }
        if (persisted) {
            base += " Statuses saved to database.";
        }
        return base;
    }

    private List<Resume> resumesForApplications(List<Application> apps) {
        List<Long> userIds = apps.stream()
                .map(a -> a.getApplicant().getId())
                .distinct()
                .collect(Collectors.toList());
        return resumeRepo.findAllWithUser().stream()
                .filter(r -> userIds.contains(r.getUser().getId()))
                .collect(Collectors.toList());
    }
}
