package xyz.wewin.autumn.compiler.model;

import java.util.List;

import javax.lang.model.element.AnnotationMirror;

import lombok.Data;

/**
 * @author: baoxin.zhao
 * @date: 2024/11/13
 */
@Data
public class TargetAnnotation {
    private String type;
    private List<TargetAnnotationAttribute> attributes;
}
