package com.hirex.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.hirex.service.AtsBulkService;
import com.hirex.dto.AtsBulkResponseDto;
import com.hirex.dto.AtsSummaryDto;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/ats")
@CrossOrigin
public class AtsBulkController {
    @Autowired
    private AtsBulkService atsBulkService;

    public AtsBulkController(AtsBulkService atsBulkService) {
        this.atsBulkService = atsBulkService;
    }

    /**
     * POST /api/ats/analyze-all
     *
     * Runs ATS scoring on every uploaded resume in the system.
     * Does NOT update any application status.
     * Safe to call multiple times.
     */
    @PostMapping("/analyze-all")
    public ResponseEntity<AtsBulkResponseDto> analyzeAll() {
        AtsBulkResponseDto response = atsBulkService.analyzeAll();
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/ats/process-all
     *
     * Runs ATS scoring on every uploaded resume AND writes the
     * derived status (SHORTLISTED / REJECTED) back to                    // CHANGED
     * every Application row for that candidate.                          // CHANGED
     * ATS scoring never assigns HIRED — HIRED is only ever set            // CHANGED
     * manually by a recruiter/manager via the Hire action.                // CHANGED
     *
     * Status mapping:                                                    // CHANGED
     *   score >= 60  →  SHORTLISTED                                      // CHANGED
     *   score <  60  →  REJECTED                                         // CHANGED
     */
    @PostMapping("/process-all")
    public ResponseEntity<AtsBulkResponseDto> processAll() {
        AtsBulkResponseDto response = atsBulkService.processAll();
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/ats/process-all/stream
     *
     * SSE endpoint: processes candidates one-by-one and streams
     * progress events to the client so the UI can show
     * "Processing Candidate X of Y" in real time.
     *
     * Event types emitted:
     *   - "progress"  : { current, total, candidate, status, score }
     *   - "complete"  : { message, totalProcessed, totalSkipped, totalHired, totalShortlisted, totalRejected }
     *   - "error"     : { message }
     */
//    @GetMapping(value = "/process-all/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
//    public SseEmitter processAllStream() {
//        SseEmitter emitter = new SseEmitter(300_000L); // 5-minute timeout
//        atsBulkService.processAllStreaming(emitter);
//        return emitter;
//    }

    /**
     * GET /api/ats/summary
     *
     * Returns aggregate counts for the manager dashboard:
     *   totalApplicants, totalHired, totalShortlisted,
     *   totalRejected, totalPending, totalWithResume
     */
    @GetMapping("/summary")
    public ResponseEntity<AtsSummaryDto> summary() {
        AtsSummaryDto dto = atsBulkService.getSummary();
        return ResponseEntity.ok(dto);
    }
}
