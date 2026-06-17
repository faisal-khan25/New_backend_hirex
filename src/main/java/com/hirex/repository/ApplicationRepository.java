package com.hirex.repository;

import com.hirex.entity.Application;
import com.hirex.entity.ApplicationStatus;
import com.hirex.entity.Job;
import com.hirex.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    // Basic methods
    List<Application> findByApplicant(User user);

    List<Application> findByJob(Job job);

    List<Application> findByJobIn(List<Job> jobs);

    boolean existsByJobAndApplicant(Job job, User user);

    Optional<Application> findByJobAndApplicant(Job job, User user);

    // Admin dashboard – count applications per company
    @Query("""
            SELECT a.job.company.name, COUNT(a)
            FROM Application a
            GROUP BY a.job.company.name
            """)
    List<Object[]> countApplicationsPerCompany();

    // Count all applications by status
    long countByStatus(ApplicationStatus status);

    // Count total applications for a company
    @Query("""
            SELECT COUNT(a)
            FROM Application a
            WHERE a.job.company.id = :companyId
            """)
    long countByCompanyId(@Param("companyId") Long companyId);

    // Count applications by company and status
    @Query("""
            SELECT COUNT(a)
            FROM Application a
            WHERE a.job.company.id = :companyId
            AND a.status = :status
            """)
    long countByCompanyIdAndStatus(
            @Param("companyId") Long companyId,
            @Param("status") ApplicationStatus status
    );

    // Find all applications belonging to a company
    @Query("""
            SELECT a
            FROM Application a
            JOIN FETCH a.applicant
            JOIN FETCH a.job
            WHERE a.job.company.id = :companyId
            """)
    List<Application> findByCompanyId(
            @Param("companyId") Long companyId
    );

    // Find applications by applicant id
    @Query("""
            SELECT a
            FROM Application a
            WHERE a.applicant.id = :userId
            ORDER BY a.appliedAt DESC
            """)
    List<Application> findByApplicantId(
            @Param("userId") Long userId
    );

    // Bulk update application status
    @Modifying
    @Query("""
            UPDATE Application a
            SET a.status = :status
            WHERE a.applicant.id = :userId
            AND a.job.id = :jobId
            """)
    int updateStatusByUserAndJob(
            @Param("userId") Long userId,
            @Param("jobId") Long jobId,
            @Param("status") ApplicationStatus status
    );

    // Get all shortlisted applications for a manager
    @Query("""
            SELECT a
            FROM Application a
            JOIN FETCH a.applicant
            JOIN FETCH a.job j
            JOIN FETCH j.company c
            WHERE c.manager = :manager
            AND a.status = :status
            ORDER BY a.appliedAt DESC
            """)
    List<Application> findShortlistedApplicationsByManager(
            @Param("manager") User manager,
            @Param("status") ApplicationStatus status
    );
//    @Query("SELECT a FROM Application a " +
//            "WHERE a.job.company.manager = :manager " +
//            "AND a.status IN (:statuses) " +
//            "ORDER BY a.appliedAt DESC")
//    List<Application> findShortlistedApplicationsByManager(
//            @Param("manager") User manager,
//            @Param("statuses") List<ApplicationStatus> statuses
//    );


    // Find applications of a candidate under a specific manager
    @Query("""
            SELECT a
            FROM Application a
            JOIN FETCH a.job j
            JOIN FETCH j.company c
            WHERE a.applicant = :candidate
            AND c.manager = :manager
            ORDER BY a.appliedAt DESC
            """)
    List<Application> findApplicationsForCandidateUnderManager(
            @Param("candidate") User candidate,
            @Param("manager") User manager
    );

}