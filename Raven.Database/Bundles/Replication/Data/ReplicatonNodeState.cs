﻿// -----------------------------------------------------------------------
//  <copyright file="ReplicatonNodeState.cs" company="Hibernating Rhinos LTD">
//      Copyright (c) Hibernating Rhinos LTD. All rights reserved.
//  </copyright>
// -----------------------------------------------------------------------
namespace Raven.Database.Bundles.Replication.Data
{
	internal enum ReplicatonNodeState
	{
		Online,
		Offline,
		Disabled
	}
}