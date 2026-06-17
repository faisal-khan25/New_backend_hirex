package com.hirex.repository;



import com.hirex.entity.Resume;
import com.hirex.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ResumeRepository extends JpaRepository<Resume, Long> {

    Optional<Resume> findByUser(User user);

    Optional<Resume> findByUserId(Long userId);

    // Fetch all resumes with their associated user (for manager view)
    @Query("SELECT r FROM Resume r JOIN FETCH r.user ORDER BY r.uploadedAt DESC")
    List<Resume> findAllWithUser();
}