package com.simonrowe.blog;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Setting;

@Document(indexName = "blogs")
@Setting(settingPath = "elasticsearch/blog-index-settings.json")
public record BlogSearchDocument(
    @Id String id,
    @Field(type = FieldType.Text) String title,
    @Field(name = "shortDescription", type = FieldType.Text) String shortDescription,
    @Field(type = FieldType.Text) String content,
    @Field(type = FieldType.Keyword) List<String> tags,
    @Field(type = FieldType.Keyword) List<String> skills,
    @Field(name = "thumbnailImage", type = FieldType.Keyword, index = false) String thumbnailImage,
    @Field(name = "createdDate", type = FieldType.Date) Instant createdDate
) {
}
