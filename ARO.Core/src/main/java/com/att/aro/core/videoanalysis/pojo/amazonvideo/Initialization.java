/*
 *  Copyright 2017 AT&T
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/
package com.att.aro.core.videoanalysis.pojo.amazonvideo;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement(name = "Initialization")
public class Initialization {

	private String range;
	
	@Override
	public String toString() {
		StringBuilder strblr = new StringBuilder(83);
		strblr.append("Initialization range:");
		strblr.append(range);
		return strblr.toString();
	}

	@XmlAttribute
	public String getRange() {
		return range;
	}

	public void setRange(String range) {
		this.range = range;
	}

}