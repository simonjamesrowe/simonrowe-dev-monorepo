package com.simonrowe.skills;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/skills")
public class SkillGroupController {

    private final SkillGroupService skillGroupService;

    public SkillGroupController(SkillGroupService skillGroupService) {
        this.skillGroupService = skillGroupService;
    }

    @GetMapping
    public List<SkillGroupSummaryDto> getAllSkillGroups() {
        return skillGroupService.getAllSkillGroups();
    }

    @GetMapping("/{id}")
    public SkillGroupDetailDto getSkillGroupById(@PathVariable String id) {
        return skillGroupService.getSkillGroupById(id);
    }
}
