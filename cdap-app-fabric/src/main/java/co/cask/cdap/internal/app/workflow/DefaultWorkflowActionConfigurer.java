/*
 * Copyright © 2015 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.internal.app.workflow;

import co.cask.cdap.api.workflow.WorkflowAction;
import co.cask.cdap.api.workflow.WorkflowActionConfigurer;
import co.cask.cdap.internal.lang.Reflections;
import co.cask.cdap.internal.specification.DataSetFieldExtractor;
import co.cask.cdap.internal.specification.PropertyFieldExtractor;
import co.cask.cdap.internal.workflow.DefaultWorkflowActionSpecification;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Default implementation of {@link WorkflowActionConfigurer}.
 */
public class DefaultWorkflowActionConfigurer implements WorkflowActionConfigurer {

  private final String className;
  private final Map<String, String> propertyFields;
  private final Set<String> datasetFields;

  private String name;
  private String description;
  private Map<String, String> properties;
  private Set<String> datasets;

  public DefaultWorkflowActionConfigurer(WorkflowAction workflowAction) {
    this.name = workflowAction.getClass().getSimpleName();
    this.className = workflowAction.getClass().getName();
    this.propertyFields = new HashMap<>();
    this.datasetFields = new HashSet<>();
    this.properties = new HashMap<>();
    this.datasets = new HashSet<>();

    Reflections.visit(workflowAction, workflowAction.getClass(), new PropertyFieldExtractor(propertyFields),
                      new DataSetFieldExtractor(datasetFields));
  }

  @Override
  public void setName(String name) {
    this.name = name;
  }

  @Override
  public void setDescription(String description) {
    this.description = description;
  }

  @Override
  public void setProperties(Map<String, String> properties) {
    this.properties = new HashMap<>(properties);
  }

  @Override
  public void useDatasets(Iterable<String> datasets) {
    for (String dataset : datasets) {
      this.datasets.add(dataset);
    }
  }

  public DefaultWorkflowActionSpecification createSpecification() {
    Map<String, String> properties = new HashMap<>(this.properties);
    properties.putAll(propertyFields);
    Set<String> datasets = new HashSet<>(this.datasets);
    datasets.addAll(datasetFields);
    return new DefaultWorkflowActionSpecification(className, name, description, properties, datasets);
  }
}
