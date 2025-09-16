package com.Excel.VCS.repository;
import com.Excel.VCS.model.Workbook;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkbookRepository extends MongoRepository<Workbook, String> {
}

