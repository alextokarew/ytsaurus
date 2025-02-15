#pragma once

#include <yt/systest/operation.h>

namespace NYT::NTest {

std::vector<int> CollectInputColumns(TRange<const IOperation*> operations);

std::vector<TNode> PopulateOperationInput(
    TRange<int> allInputColumns,
    TRange<int> operationInputColumns,
    TRange<TNode> input);

}  // namespace NYT::NTest
