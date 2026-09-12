package com.docmind.domain.repo;

import com.docmind.domain.KbVisitor;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KbVisitorRepository extends JpaRepository<KbVisitor, KbVisitor.Pk> {
}
