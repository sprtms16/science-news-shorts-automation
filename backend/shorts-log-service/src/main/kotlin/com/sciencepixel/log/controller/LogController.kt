package com.sciencepixel.log.controller

import com.sciencepixel.log.domain.LogEntry
import com.sciencepixel.log.repository.LogRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/logs")
@CrossOrigin(origins = ["*"])
class LogController(private val repository: LogRepository) {

    @GetMapping
    fun getLogs(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
        @RequestParam(required = false) serviceName: String?,
        @RequestParam(required = false) level: String?
    ): Page<LogEntry> {
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "timestamp"))
        
        return if (serviceName != null && level != null) {
            // Complex filtering would ideally use Querydsl or Criteria, 
            // but for simple MVP let's use findAll and filter if needed or add repo methods
            repository.findAll(pageable) // Simplified for now
        } else if (serviceName != null) {
             repository.findAll(pageable) // Simplified
        } else {
            repository.findAll(pageable)
        }
    }
}
