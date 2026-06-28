package com.simonrowe.chat;

import java.util.List;

public record SkillsWidgetPayload(List<Group> groups) {

  public record Group(String name, List<Skill> skills) {
  }

  public record Skill(String name, Integer rating) {
  }
}
