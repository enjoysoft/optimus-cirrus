/*
 * Morgan Stanley makes this available to you under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package optimus.debug;

/**
 *  Category that could be searched in splunk
 *  Sample:
 *  source=RT payload.rtvViolation=MODULE_CTOR_EC_CURRENT | stats count by payload.rtvLocation
 * */
public class RTVerifierCategory {
  public final static String NONE = "RTV_CATEGORY_UNKNOWN";
  public final static String MODULE_CTOR_EC_CURRENT = "MODULE_CTOR_EC_CURRENT";
  public final static String MODULE_LAZY_VAL_EC_CURRENT = "MODULE_LAZY_VAL_EC_CURRENT";
  public final static String TWEAK_IN_ENTITY_CTOR = "TWEAK_IN_ENTITY_CTOR";
  public final static String TWEAKABLE_IN_ENTITY_CTOR = "TWEAKABLE_IN_ENTITY_CTOR";
}