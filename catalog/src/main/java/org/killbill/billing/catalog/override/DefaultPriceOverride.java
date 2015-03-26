/*
 * Copyright 2014-2015 Groupon, Inc
 * Copyright 2014-2015 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.catalog.override;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.joda.time.DateTime;
import org.killbill.billing.ErrorCode;
import org.killbill.billing.callcontext.InternalCallContext;
import org.killbill.billing.callcontext.InternalTenantContext;
import org.killbill.billing.catalog.DefaultPlan;
import org.killbill.billing.catalog.DefaultPlanPhase;
import org.killbill.billing.catalog.DefaultPlanPhasePriceOverride;
import org.killbill.billing.catalog.api.CatalogApiException;
import org.killbill.billing.catalog.api.CatalogUserApi;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.catalog.api.Plan;
import org.killbill.billing.catalog.api.PlanPhase;
import org.killbill.billing.catalog.api.PlanPhasePriceOverride;
import org.killbill.billing.catalog.api.PlanPhaseSpecifier;
import org.killbill.billing.catalog.api.StaticCatalog;
import org.killbill.billing.catalog.dao.CatalogOverrideDao;
import org.killbill.billing.catalog.dao.CatalogOverridePhaseDefinitionModelDao;
import org.killbill.billing.catalog.dao.CatalogOverridePlanDefinitionModelDao;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.inject.Inject;
import sun.org.mozilla.javascript.internal.ast.ErrorCollector;

public class DefaultPriceOverride implements PriceOverride {

    public static final Pattern CUSTOM_PLAN_NAME_PATTERN = Pattern.compile("(.*)-(\\d+)$");

    private final CatalogOverrideDao overrideDao;

    @Inject
    public DefaultPriceOverride(final CatalogOverrideDao overrideDao) {
        this.overrideDao = overrideDao;
    }

    @Override
    public DefaultPlan getOrCreateOverriddenPlan(final Plan parentPlan, final DateTime catalogEffectiveDate, final List<PlanPhasePriceOverride> overrides, final InternalCallContext context) throws CatalogApiException {

        final PlanPhasePriceOverride[] resolvedOverride = new PlanPhasePriceOverride[parentPlan.getAllPhases().length];
        int index = 0;
        for (final PlanPhase curPhase : parentPlan.getAllPhases()) {
            final PlanPhasePriceOverride curOverride = Iterables.tryFind(overrides, new Predicate<PlanPhasePriceOverride>() {
                @Override
                public boolean apply(final PlanPhasePriceOverride input) {
                    if (input.getPhaseName() != null) {
                        return input.getPhaseName().equals(curPhase.getName());
                    }
                    // If the phaseName was not passed, we infer by matching the phaseType. This obvously would not work in a case where
                    // a plan is defined with multiple phases of the same type.
                    final PlanPhaseSpecifier curPlanPhaseSpecifier = input.getPlanPhaseSpecifier();
                    if (curPlanPhaseSpecifier.getPhaseType().equals(curPhase.getPhaseType())) {
                        return true;
                    }
                    return false;
                }
            }).orNull();
            resolvedOverride[index++] = curOverride != null ?
                                        new DefaultPlanPhasePriceOverride(curPhase.getName(), curOverride.getCurrency(), curOverride.getFixedPrice(), curOverride.getRecurringPrice()) :
                                        null;
        }

        for (int i = 0; i < resolvedOverride.length; i++) {
            final PlanPhasePriceOverride curOverride = resolvedOverride[i];
            if (curOverride != null) {
                final DefaultPlanPhase curPhase = (DefaultPlanPhase) parentPlan.getAllPhases()[i];

                if (curPhase.getFixed() == null && curOverride.getFixedPrice() != null) {
                    final String error = String.format("There is no existing fixed price for the phase %s", curPhase.getName());
                    throw new CatalogApiException(ErrorCode.CAT_INVALID_INVALID_PRICE_OVERRIDE, parentPlan.getName(), error);
                }

                if (curPhase.getRecurring() == null && curOverride.getRecurringPrice() != null) {
                    final String error = String.format("There is no existing recurring price for the phase %s", curPhase.getName());
                    throw new CatalogApiException(ErrorCode.CAT_INVALID_INVALID_PRICE_OVERRIDE, parentPlan.getName(), error);
                }
            }
        }

        final CatalogOverridePlanDefinitionModelDao overriddenPlan = overrideDao.getOrCreateOverridePlanDefinition(parentPlan.getName(), catalogEffectiveDate, resolvedOverride, context);
        final String planName = new StringBuffer(parentPlan.getName()).append("-").append(overriddenPlan.getRecordId()).toString();
        final DefaultPlan result = new DefaultPlan(planName, (DefaultPlan) parentPlan, resolvedOverride);
        return result;
    }

    @Override
    public DefaultPlan getOverriddenPlan(final String planName, final StaticCatalog catalog, final InternalTenantContext context) throws CatalogApiException {

        final Matcher m = CUSTOM_PLAN_NAME_PATTERN.matcher(planName);
        if (!m.matches()) {
            throw new CatalogApiException(ErrorCode.CAT_NO_SUCH_PLAN, planName);
        }
        final String parentPlanName = m.group(1);
        final Long planDefRecordId = Long.parseLong(m.group(2));

        final List<CatalogOverridePhaseDefinitionModelDao> phaseDefs = overrideDao.getOverriddenPlanPhases(planDefRecordId, context);
        final DefaultPlan defaultPlan = (DefaultPlan) catalog.findCurrentPlan(parentPlanName);

        final PlanPhasePriceOverride[] overrides = createOverrides(defaultPlan, phaseDefs);
        return new DefaultPlan(planName, defaultPlan, overrides);
    }

    private PlanPhasePriceOverride[] createOverrides(final Plan defaultPlan, final List<CatalogOverridePhaseDefinitionModelDao> phaseDefs) {

        final PlanPhasePriceOverride[] result = new PlanPhasePriceOverride[defaultPlan.getAllPhases().length];

        for (int i = 0; i < defaultPlan.getAllPhases().length; i++) {

            final PlanPhase curPhase = defaultPlan.getAllPhases()[i];
            final CatalogOverridePhaseDefinitionModelDao overriddenPhase = Iterables.tryFind(phaseDefs, new Predicate<CatalogOverridePhaseDefinitionModelDao>() {
                @Override
                public boolean apply(final CatalogOverridePhaseDefinitionModelDao input) {
                    return input.getParentPhaseName().equals(curPhase.getName());
                }
            }).orNull();
            result[i] = (overriddenPhase != null) ?
                        new DefaultPlanPhasePriceOverride(curPhase.getName(), Currency.valueOf(overriddenPhase.getCurrency()), overriddenPhase.getFixedPrice(), overriddenPhase.getRecurringPrice()) :
                        null;
        }
        return result;
    }

}
