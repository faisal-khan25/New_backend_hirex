package com.hirex.repository;

import com.hirex.entity.Company;
import com.hirex.entity.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface JobRepository extends JpaRepository<Job, Long> {
    List<Job> findByActiveTrue();
    List<Job> findByCompany(Company company);
    List<Job> findByTitleContainingIgnoreCaseAndActiveTrue(String keyword);
}
