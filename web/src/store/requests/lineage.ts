// Copyright 2018-2023 contributors to the Marquez project
// SPDX-License-Identifier: Apache-2.0

import { API_URL } from '../../globals'
import { JobOrDataset } from '../../types/lineage'
import { generateNodeId } from '../../helpers/nodes'
import { genericFetchWrapper } from './index'

const LINEAGE_API_URL = API_URL.endsWith('/api/v1')
  ? `${API_URL.slice(0, -'/api/v1'.length)}/api/v2`
  : API_URL

export const getLineage = async (
  nodeType: JobOrDataset,
  namespace: string,
  name: string,
  depth: number
) => {
  const encodedNamespace = encodeURIComponent(namespace)
  const encodedName = encodeURIComponent(name)
  const nodeId = generateNodeId(nodeType, encodedNamespace, encodedName)
  const url = `${LINEAGE_API_URL}/lineage?nodeId=${nodeId}&depth=${depth}`
  return genericFetchWrapper(url, { method: 'GET' }, 'fetchLineage')
}
