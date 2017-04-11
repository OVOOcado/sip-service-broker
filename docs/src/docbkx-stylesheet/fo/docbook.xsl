<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="1.0">

  <!-- imports the original docbook stylesheet -->
  <xsl:import href="urn:docbkx:stylesheet"/>

  <!-- set bellow all your custom xsl configuration -->

  <xsl:param name="page.margin.outer">0.5in</xsl:param>
  <xsl:param name="page.margin.inner">0.5in</xsl:param>
  <xsl:param name="hyphenate">false</xsl:param>
  <xsl:param name="section.autolabel" select="1"/>
  <xsl:param name="section.label.includes.component.label" select="1"/>
  <xsl:param name="bibliography.numbered" select="1"></xsl:param>

  <xsl:attribute-set name="xref.properties">
    <xsl:attribute name="font-style">italic</xsl:attribute>
  </xsl:attribute-set>


  <xsl:attribute-set name="chapter.title.level2.properties">
    <xsl:attribute name="font-size">16pt</xsl:attribute>
  </xsl:attribute-set>

  <!--
    Important links:
    - http://www.sagehill.net/docbookxsl/
    - http://docbkx-tools.sourceforge.net/
  -->

</xsl:stylesheet>