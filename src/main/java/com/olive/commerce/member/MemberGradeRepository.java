package com.olive.commerce.member;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MemberGradeRepository extends JpaRepository<MemberGrade, Long> {

    @Query("select g.id from MemberGrade g where g.name = :name")
    Long findIdByName(String name);
}
