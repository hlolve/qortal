package org.qortal.data.arbitrary;

import org.qortal.arbitrary.metadata.ArbitraryDataTransactionMetadata;
import org.qortal.arbitrary.misc.Category;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
public class ArbitraryResourceMetadata {

    private String title;
    private String description;
    private List<String> tags;
    private Category category;
    private String categoryName;

    public ArbitraryResourceMetadata() {
    }

    public ArbitraryResourceMetadata(String title, String description, List<String> tags, Category category) {
        this.title = title;
        this.description = description;
        this.tags = tags;
        this.category = category;
        this.categoryName = category.getName();
    }

    public static ArbitraryResourceMetadata fromTransactionMetadata(ArbitraryDataTransactionMetadata transactionMetadata) {
        if (transactionMetadata == null) {
            return null;
        }
        String title = transactionMetadata.getTitle();
        String description = transactionMetadata.getDescription();
        List<String> tags = transactionMetadata.getTags();
        Category category = transactionMetadata.getCategory();

        if (title == null && description == null && tags == null && category == null) {
            return null;
        }

        return new ArbitraryResourceMetadata(title, description, tags, category);
    }
}
