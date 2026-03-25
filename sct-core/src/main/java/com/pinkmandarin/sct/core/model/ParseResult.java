package com.pinkmandarin.sct.core.model;

import java.util.List;

public record ParseResult(List<Property> properties, List<Environment> environments) {}
